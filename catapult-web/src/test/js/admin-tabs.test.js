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

    describe("patching a view after an edit", () => {
        /** The caches tab as the server renders it with `rows` ([key, text]) and a create form. */
        const view = (rows, { count = rows.length, at = "2026-01-01T00:00:00Z" } = {}) => `
            <div class="view-root">
                <span data-admin-value="count">${count} rows</span>
                <div data-admin-value="empty" ${rows.length ? "hidden" : ""}>None</div>
                <div data-admin-list="rows" ${rows.length ? "" : "hidden"}>
                    <div class="head">Head</div>
                    ${rows.map(([key, text]) => `<div class="row" data-admin-key="${key}"><b>${text}</b>
                        <time data-live-time="${at}">rendered</time><button data-x="${text}"></button></div>`).join("")}
                </div>
                <form class="create"><input name="key"><input name="mode" value="NAME"></form>
            </div>`;

        let served;
        const rowOf = key => panel().querySelector(`[data-admin-key="${key}"]`);
        const keys = () => Array.from(panel().querySelectorAll("[data-admin-key]")).map(row => row.dataset.adminKey);

        async function shownWith(rows) {
            served = view(rows);
            fetched.mockImplementation(async () => response(200, served));
            page("caches");
            await flush();
        }

        it("inserts a new row at its place, removes a gone one and patches a changed one, leaving the rest", async () => {
            await shownWith([["a", "A"], ["b", "B"], ["c", "C"]]);
            const a = rowOf("a");
            const b = rowOf("b");
            const field = panel().querySelector('[name="key"]');
            field.value = "typing";
            served = view([["a", "A"], ["new", "New"], ["b", "B2"]]);

            await CatapultAdminTabs.patch(panel());

            expect(keys()).toEqual(["a", "new", "b"]);
            expect(rowOf("a")).toBe(a);
            expect(rowOf("b")).toBe(b);
            expect(b.querySelector("b").textContent).toBe("B2");
            expect(b.querySelector("button").dataset.x).toBe("B2");
            expect(panel().querySelector('[data-admin-value="count"]').textContent).toBe("3 rows");
            expect(panel().querySelector(".head").textContent).toBe("Head");
            expect(panel().querySelector('[name="key"]')).toBe(field);
            expect(field.value).toBe("typing");
            expect(urls()).toEqual(["/spa/admin/caches", "/spa/admin/caches"]);
        });

        it("shows the empty state, then the list again, by their hidden attribute", async () => {
            await shownWith([["a", "A"]]);
            const list = panel().querySelector("[data-admin-list]");
            served = view([]);

            await CatapultAdminTabs.patch(panel());
            expect(keys()).toEqual([]);
            expect(list.hidden).toBe(true);
            expect(panel().querySelector('[data-admin-value="empty"]').hidden).toBe(false);

            served = view([["z", "Z"]]);
            await CatapultAdminTabs.patch(panel());
            expect(panel().querySelector("[data-admin-list]")).toBe(list);
            expect(list.hidden).toBe(false);
            expect(keys()).toEqual(["z"]);
        });

        it("leaves a row the server renders the same alone, whatever a script did to it", async () => {
            await shownWith([["a", "A"]]);
            const time = rowOf("a").querySelector("time");
            time.textContent = "3 minutes ago";
            rowOf("a").setAttribute("data-busy", "");

            await CatapultAdminTabs.patch(panel());

            expect(time.textContent).toBe("3 minutes ago");
            expect(rowOf("a").hasAttribute("data-busy")).toBe(true);
        });

        it("keeps a live time's text in a patched row unless its time changed", async () => {
            await shownWith([["a", "A"]]);
            const time = rowOf("a").querySelector("time");
            time.textContent = "3 minutes ago";
            served = view([["a", "A2"]]);
            await CatapultAdminTabs.patch(panel());
            expect(rowOf("a").querySelector("time")).toBe(time);
            expect(time.textContent).toBe("3 minutes ago");

            served = view([["a", "A2"]], { at: "2026-02-01T00:00:00Z" });
            await CatapultAdminTabs.patch(panel());
            expect(rowOf("a").querySelector("time")).not.toBe(time);
            expect(rowOf("a").querySelector("time").dataset.liveTime).toBe("2026-02-01T00:00:00Z");
        });

        it("announces the inserted rows only", async () => {
            await shownWith([["a", "A"]]);
            const announced = [];
            document.addEventListener("catapult:admin-panel", event => announced.push(event.detail));
            served = view([["a", "A"], ["b", "B"]]);

            await CatapultAdminTabs.patch(panel());

            expect(announced).toHaveLength(1);
            expect(announced[0].tab).toBe("caches");
            expect(announced[0].panel).toBe(panel());
            expect(announced[0].root).toBe(rowOf("b"));
        });

        it("renders the whole view again when its marked parts no longer match (a list now unavailable)", async () => {
            await shownWith([["a", "A"]]);
            const announced = vi.fn();
            document.addEventListener("catapult:admin-panel", event => announced(event.detail.root));
            served = `<p class="view">unavailable</p>`;

            await CatapultAdminTabs.patch(panel());

            expect(shown()).toBe("unavailable");
            expect(announced).toHaveBeenCalledWith(panel());
        });

        it("says so when the view can't be fetched again, leaving it as it was", async () => {
            await shownWith([["a", "A"]]);
            fetched.mockRejectedValueOnce(new TypeError("offline"));

            await CatapultAdminTabs.patch(panel());

            expect(document.querySelector("mdui-snackbar").textContent).toBe("Unavailable");
            expect(keys()).toEqual(["a"]);
        });

        it("drops the answer of a view the admin left meanwhile", async () => {
            await shownWith([["a", "A"]]);
            let answer;
            fetched.mockImplementationOnce(() => new Promise(resolve => { answer = () => resolve(response(200, view([]))); }));
            const patching = CatapultAdminTabs.patch(panel());
            served = `<p class="view">other</p>`;
            history.replaceState(null, "", "/admin#caches/other");
            await CatapultAdminTabs.show(panel());
            answer();
            await patching;

            expect(shown()).toBe("other");
        });

        it("patches a searched or paged view from the results it shows, or from the URL a tab script gives", async () => {
            served = view([["a", "A"]]);
            fetched.mockImplementation(async () => response(200, served));
            history.replaceState(null, "", "/admin#caches/igdb-game-cache");
            page();
            await flush();
            panel().insertAdjacentHTML("beforeend", `<div data-admin-results></div><button data-admin-page="2"></button>`);
            panel().querySelector('[data-admin-page="2"]').click();
            await flush();

            await CatapultAdminTabs.patch(panel());
            await CatapultAdminTabs.patch(panel(), "/spa/admin/caches?status=OTHER");

            expect(urls().slice(-2)).toEqual(["/spa/admin/caches/igdb-game-cache?q=&page=2", "/spa/admin/caches?status=OTHER"]);
        });

        it("keeps an attribute a component reflected onto itself in a morphed row, removes one the server dropped", async () => {
            if (!customElements.get("x-reflecting")) {
                customElements.define("x-reflecting", class extends HTMLElement {
                    static elementProperties = new Map([["variant", { reflect: true }], ["placement", { reflect: true, attribute: "placement" }]]);
                    connectedCallback() {
                        if (!this.hasAttribute("variant")) this.setAttribute("variant", "assist");
                    }
                });
            }
            const row = (text, extra) => `<div data-admin-list="rows"><div data-admin-key="a"><x-reflecting ${extra}>${text}</x-reflecting></div></div>`;
            served = row("A", 'data-old="1"');
            fetched.mockImplementation(async () => response(200, served));
            page("caches");
            await flush();
            const chip = panel().querySelector("x-reflecting");
            expect(chip.getAttribute("variant")).toBe("assist");

            served = row("A2", "");
            await CatapultAdminTabs.patch(panel());

            expect(panel().querySelector("x-reflecting")).toBe(chip);
            expect(chip.textContent).toBe("A2");
            expect(chip.getAttribute("variant")).toBe("assist");
            expect(chip.hasAttribute("data-old")).toBe(false);
        });

        it("without the server's previous render of a row, removes only attributes no component reflects", async () => {
            await shownWith([["a", "A"]]);
            const list = panel().querySelector("[data-admin-list]");
            list.insertAdjacentHTML("beforeend", `<div class="row" data-admin-key="z"><x-reflecting variant="assist" data-stale="1">Z</x-reflecting></div>`);
            served = view([["a", "A"]]).replace("</div>\n                <form", `<div class="row" data-admin-key="z"><x-reflecting>Z2</x-reflecting></div></div>\n                <form`);
            customElements.get("x-reflecting") ?? customElements.define("x-reflecting", class extends HTMLElement {
                static elementProperties = new Map([["variant", { reflect: true }]]);
            });

            await CatapultAdminTabs.patch(panel());

            const chip = rowOf("z").querySelector("x-reflecting");
            expect(chip.textContent).toBe("Z2");
            expect(chip.getAttribute("variant")).toBe("assist");
            expect(chip.hasAttribute("data-stale")).toBe(false);
        });

        it("leaves the list's own element alone while the server renders it the same", async () => {
            await shownWith([["a", "A"]]);
            const list = panel().querySelector("[data-admin-list]");
            list.setAttribute("data-busy", "");
            served = view([["a", "A"], ["b", "B"]]);

            await CatapultAdminTabs.patch(panel());

            expect(list.hasAttribute("data-busy")).toBe(true);
            expect(keys()).toEqual(["a", "b"]);
        });

        it("reorders kept rows without patching them, and keeps a plain row between keyed ones in its place", async () => {
            const sorted = order => `<div data-admin-list="rows">
                <div class="head">Head</div>
                ${order.map(key => key === "-" ? `<div class="divider">-</div>` : `<div data-admin-key="${key}">${key}</div>`).join("")}
            </div>`;
            served = sorted(["a", "-", "b", "c"]);
            fetched.mockImplementation(async () => response(200, served));
            page("caches");
            await flush();
            const [a, b, c] = ["a", "b", "c"].map(rowOf);
            served = sorted(["c", "-", "a", "b"]);

            await CatapultAdminTabs.patch(panel());

            const children = Array.from(panel().querySelector("[data-admin-list]").children);
            expect(children.map(child => child.dataset.adminKey ?? child.className)).toEqual(["head", "c", "divider", "a", "b"]);
            expect([rowOf("a"), rowOf("b"), rowOf("c")]).toEqual([a, b, c]);
        });

        it("drops a patch's answer once a newer patch started after it", async () => {
            await shownWith([["a", "A"]]);
            let answerOld;
            fetched.mockImplementationOnce(() => new Promise(resolve => { answerOld = () => resolve(response(200, view([["old", "Old"]]))); }));
            const older = CatapultAdminTabs.patch(panel());
            served = view([["a", "A"], ["new", "New"]]);
            await CatapultAdminTabs.patch(panel());
            answerOld();
            await older;

            expect(keys()).toEqual(["a", "new"]);
        });

        it("renders a view never shown as a whole", async () => {
            page("members");
            await flush();

            await CatapultAdminTabs.patch(panel());

            expect(shown()).toBe("/spa/admin/caches");
        });

        it("empties a form's fields back to their rendered value", async () => {
            await shownWith([]);
            const form = panel().querySelector(".create");
            form.querySelector('[name="key"]').value = "typed";
            form.querySelector('[name="key"]').setAttribute("invalid", "");
            form.querySelector('[name="mode"]').value = "PROPERTY";

            CatapultAdminTabs.reset(form);

            expect(form.querySelector('[name="key"]').value).toBe("");
            expect(form.querySelector('[name="key"]').hasAttribute("invalid")).toBe(false);
            expect(form.querySelector('[name="mode"]').value).toBe("NAME");
        });
    });
});
