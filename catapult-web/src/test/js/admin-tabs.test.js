/*
 * The admin page's lazy tabs: each one's content is fetched the first time it is shown, its
 * sub-view comes from the hash (#caches/igdb-game-cache), search and pagination only swap the
 * results, and a failed fetch says so and is retried on the next show.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let fetched;
const urls = () => fetched.mock.calls.map(([url]) => url);

/** A fragment naming the URL it came from, with every control admin-tabs.js handles. */
function fragment(url) {
    return `<p class="view">${url}</p>
        <mdui-text-field data-admin-search></mdui-text-field>
        <button data-admin-open="igdb-game-cache"></button>
        <button data-admin-back></button>
        <button data-admin-retry></button>
        <div data-admin-results><span class="results">${url}</span><button data-admin-page="2"></button></div>`;
}

function page(active = "members") {
    html(`
        <mdui-tabs id="admin-tabs" data-hash-tabs>
            <mdui-tab value="members"></mdui-tab><mdui-tab value="caches"></mdui-tab>
            <mdui-tab-panel value="members"></mdui-tab-panel>
            <mdui-tab-panel value="caches" data-admin-lazy data-unavailable="Unavailable"></mdui-tab-panel>
        </mdui-tabs>`);
    const tabs = document.getElementById("admin-tabs");
    tabs.value = active;
    render();
    return tabs;
}

const panel = () => document.querySelector('mdui-tab-panel[value="caches"]');
const shown = () => panel().querySelector(".view")?.textContent;
const results = () => panel().querySelector(".results")?.textContent;

async function click(selector) {
    panel().querySelector(selector).click();
    await flush();
}

beforeEach(async () => {
    history.replaceState(null, "", "/admin");
    fetched = vi.fn(async url => response(200, fragment(url)));
    window.fetch = fetched;
    window.CatapultCsrf = { postJson: vi.fn() };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    window.mdui = { confirm: vi.fn(() => Promise.resolve()) };
    await load("channel-page", "admin-tabs");
});

afterEach(() => {
    vi.useRealTimers();
});

describe("admin-tabs", () => {
    it("loads a lazy tab the first time it is shown, and only then", async () => {
        const tabs = page();
        await flush();
        expect(urls()).toEqual([]);

        tabs.value = "caches";
        tabs.dispatchEvent(new Event("change"));
        await flush();
        tabs.dispatchEvent(new Event("change"));
        await flush();

        expect(urls()).toEqual(["/spa/admin/caches"]);
        expect(shown()).toBe("/spa/admin/caches");
    });

    it("opens the sub-view named in the hash", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page();
        await flush();

        expect(urls()).toEqual(["/spa/admin/caches/igdb-game-cache"]);
    });

    it("keeps the slash of a nested sub-view, and the page's query string for a debug page's filters", async () => {
        history.replaceState(null, "", "/admin?service=steam&page=1#caches/abc%2Fdebug");
        page();
        await flush();

        expect(urls()).toEqual(["/spa/admin/caches/abc/debug?service=steam&page=1"]);
    });

    it("opens a sub-view as a new history entry, then goes back to the list", async () => {
        page("caches");
        await flush();
        const before = history.length;

        await click("[data-admin-open]");
        expect(location.hash).toBe("#caches/igdb-game-cache");
        expect(history.length).toBe(before + 1);
        expect(shown()).toBe("/spa/admin/caches/igdb-game-cache");

        await click("[data-admin-back]");
        expect(location.hash).toBe("#caches");
        expect(shown()).toBe("/spa/admin/caches");
    });

    it("searches once typing pauses, swapping only the results", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page();
        await flush();
        const field = panel().querySelector("[data-admin-search]");

        vi.useFakeTimers();
        field.value = "do";
        field.dispatchEvent(new Event("input", { bubbles: true }));
        field.value = "doom";
        field.dispatchEvent(new Event("input", { bubbles: true }));
        await vi.advanceTimersByTimeAsync(300);
        vi.useRealTimers();
        await flush();

        expect(urls().slice(1)).toEqual(["/spa/admin/caches/igdb-game-cache?q=doom&page=0"]);
        expect(results()).toBe("/spa/admin/caches/igdb-game-cache?q=doom&page=0");
        expect(panel().querySelector("[data-admin-search]")).toBe(field);
        expect(shown()).toBe("/spa/admin/caches/igdb-game-cache");
    });

    it("keeps the latest search when an older answer comes back last", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page();
        await flush();
        let answerOld;
        fetched.mockImplementationOnce(url => new Promise(resolve => { answerOld = () => resolve(response(200, fragment(url))); }));

        await click('[data-admin-page="2"]');
        const field = panel().querySelector("[data-admin-search]");
        vi.useFakeTimers();
        field.value = "doom";
        field.dispatchEvent(new Event("input", { bubbles: true }));
        await vi.advanceTimersByTimeAsync(300);
        vi.useRealTimers();
        await flush();
        answerOld();
        await flush();

        expect(results()).toBe("/spa/admin/caches/igdb-game-cache?q=doom&page=0");
    });

    it("says so when a search or page can't be fetched, leaving the results as they were", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page();
        await flush();
        fetched.mockRejectedValueOnce(new TypeError("offline"));

        await click('[data-admin-page="2"]');

        expect(document.querySelector("mdui-snackbar").textContent).toBe("Unavailable");
        expect(results()).toBe("/spa/admin/caches/igdb-game-cache");
    });

    it("opens the view of a hash with a malformed escape, as typed", async () => {
        history.replaceState(null, "", "/admin#caches/%E0%A4%A");
        page();
        await flush();

        expect(urls()).toEqual(["/spa/admin/caches/%25E0%25A4%25A"]);
    });

    it("pages with the current search", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page();
        await flush();

        await click('[data-admin-page="2"]');

        expect(urls().at(-1)).toBe("/spa/admin/caches/igdb-game-cache?q=&page=2");
    });

    it("reloads the view on retry", async () => {
        page("caches");
        await flush();

        await click("[data-admin-retry]");

        expect(urls()).toEqual(["/spa/admin/caches", "/spa/admin/caches"]);
    });

    it("says so when the fetch fails, and tries again the next time the tab is shown", async () => {
        fetched.mockRejectedValueOnce(new TypeError("offline"));
        const tabs = page("caches");
        await flush();

        expect(document.querySelector("mdui-snackbar").textContent).toBe("Unavailable");

        tabs.dispatchEvent(new Event("change"));
        await flush();
        expect(shown()).toBe("/spa/admin/caches");
    });

    it("keeps the view when a field inside the panel changes", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page();
        await flush();
        panel().insertAdjacentHTML("beforeend", `<mdui-dialog><mdui-text-field name="name"></mdui-text-field></mdui-dialog>`);

        // mdui's text fields and selects emit a bubbling change on blur, Enter or clear.
        panel().querySelector("[data-admin-search]").dispatchEvent(new Event("change", { bubbles: true }));
        await flush();
        panel().querySelector('[name="name"]').dispatchEvent(new Event("change", { bubbles: true }));
        await flush();

        expect(location.hash).toBe("#caches/igdb-game-cache");
        expect(urls()).toEqual(["/spa/admin/caches/igdb-game-cache"]);
        expect(shown()).toBe("/spa/admin/caches/igdb-game-cache");
        expect(panel().querySelector("mdui-dialog")).not.toBeNull();
    });

    it("drops a search still pending or on its way when another view is shown", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page();
        await flush();
        const field = panel().querySelector("[data-admin-search]");

        vi.useFakeTimers();
        field.value = "doom";
        field.dispatchEvent(new Event("input", { bubbles: true }));
        await vi.advanceTimersByTimeAsync(100);
        panel().querySelector("[data-admin-back]").click();
        await vi.advanceTimersByTimeAsync(300);
        vi.useRealTimers();
        await flush();

        expect(urls()).toEqual(["/spa/admin/caches/igdb-game-cache", "/spa/admin/caches"]);
        expect(shown()).toBe("/spa/admin/caches");

        // A search already sent: its answer lands after the next view opened.
        let answer;
        fetched.mockImplementationOnce(url => new Promise(resolve => { answer = () => resolve(response(200, fragment(url))); }));
        await click('[data-admin-page="2"]');
        await click("[data-admin-open]");
        answer();
        await flush();

        expect(shown()).toBe("/spa/admin/caches/igdb-game-cache");
        expect(results()).toBe("/spa/admin/caches/igdb-game-cache");
    });

    it("ignores a click on a plain element of a loaded panel", async () => {
        history.replaceState(null, "", "/admin#caches");
        page("caches");
        await flush();
        expect(urls()).toEqual(["/spa/admin/caches"]);

        await click(".view");

        expect(urls()).toEqual(["/spa/admin/caches"]);
        expect(document.querySelector("mdui-snackbar")).toBeNull();
    });

    it("does not reload a sub-view's results on a click outside its controls", async () => {
        history.replaceState(null, "", "/admin#caches/igdb-game-cache");
        page("caches");
        await flush();

        await click(".view");
        await click(".results");

        expect(urls()).toEqual(["/spa/admin/caches/igdb-game-cache"]);
        expect(panel().querySelector("[data-admin-results]").parentElement).toBe(panel());
    });

    it("announces each new content of a panel", async () => {
        const announced = vi.fn();
        document.addEventListener("catapult:admin-panel", event => announced(event.detail.tab));
        page("caches");
        await flush();

        expect(announced).toHaveBeenCalledWith("caches");
    });

    describe("a prerendered tab's top-level list (Members)", () => {
        const members = () => document.querySelector('mdui-tab-panel[value="members"]');

        function prerendered() {
            html(`
                <mdui-tabs id="admin-tabs" data-hash-tabs>
                    <mdui-tab value="members"></mdui-tab>
                    <mdui-tab-panel value="members" data-admin-prerendered data-unavailable="Unavailable">
                        <mdui-text-field data-admin-search></mdui-text-field>
                        <div data-admin-results><span class="results">prerendered</span><button data-admin-page="1"></button></div>
                    </mdui-tab-panel>
                </mdui-tabs>`);
            document.getElementById("admin-tabs").value = "members";
            render();
        }

        it("pages its list without refetching the tab", async () => {
            prerendered();
            await flush();

            members().querySelector('[data-admin-page="1"]').click();
            await flush();

            expect(urls()).toEqual(["/spa/admin/members?q=&page=1"]);
            expect(members().querySelector(".results").textContent).toBe("/spa/admin/members?q=&page=1");
        });

        it("searches it from the first page, and keeps the search for the next page", async () => {
            prerendered();
            await flush();
            const field = members().querySelector("[data-admin-search]");

            vi.useFakeTimers();
            field.value = "bob";
            field.dispatchEvent(new Event("input", { bubbles: true }));
            await vi.advanceTimersByTimeAsync(300);
            vi.useRealTimers();
            await flush();
            members().querySelector('[data-admin-page="2"]').click();
            await flush();

            expect(urls()).toEqual(["/spa/admin/members?q=bob&page=0", "/spa/admin/members?q=bob&page=2"]);
        });
    });

    it("drops the sub-view that answers after the admin went back to the list", async () => {
        page("caches");
        await flush();
        let answerSub;
        fetched.mockImplementationOnce(url => new Promise(resolve => { answerSub = () => resolve(response(200, fragment(url))); }));

        await click("[data-admin-open]");
        await click("[data-admin-back]");
        answerSub();
        await flush();

        expect(shown()).toBe("/spa/admin/caches");
        expect(location.hash).toBe("#caches");
    });
});
