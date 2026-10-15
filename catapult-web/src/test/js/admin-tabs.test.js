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

    it("announces each new content of a panel", async () => {
        const announced = vi.fn();
        document.addEventListener("catapult:admin-panel", event => announced(event.detail.tab));
        page("caches");
        await flush();

        expect(announced).toHaveBeenCalledWith("caches");
    });
});
