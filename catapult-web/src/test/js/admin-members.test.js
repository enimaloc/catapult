/*
 * The admin members tab: the bot switch only posts, every other action posts once confirmed and
 * then re-renders the page; "migrate" goes through its dialog instead.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;

const posts = () => posted.mock.calls.map(([url, body]) => [url, body]);
const refreshed = () => CatapultSpa.navigate.mock.calls.length;

const ID = "00000000-0000-0000-0000-000000000001";
const OTHER = "00000000-0000-0000-0000-000000000002";

function page() {
    html(`
        <div id="admin-members" data-confirm-text="OK" data-cancel-text="No">
            <div class="admin-member" data-member-id="${ID}" data-member-name="enimaloc">
                <input type="checkbox" class="admin-member-bot-toggle" checked>
                <mdui-menu-item data-action="delete" data-confirm="Delete enimaloc?"></mdui-menu-item>
                <mdui-menu-item data-action="steam/unlink" data-confirm="Unlink?"></mdui-menu-item>
                <mdui-menu-item data-action="migrate"></mdui-menu-item>
                <mdui-menu-item data-action="debug-enable"></mdui-menu-item>
                <mdui-menu-item data-action="debug/disable"></mdui-menu-item>
                <mdui-menu-item data-action="debug/clear" data-confirm="Delete the recording?"></mdui-menu-item>
            </div>
        </div>
        <div id="admin-debug-dialog" data-headline="Debug mode for {0}">
            <span id="admin-debug-headline"></span>
            <input type="checkbox" id="admin-debug-reveal">
            <p id="admin-debug-warning" hidden></p>
            <button id="admin-debug-cancel"></button>
            <button id="admin-debug-submit"></button>
        </div>
        <div id="admin-migrate-dialog" data-headline="Migrate the data of {0}" data-incomplete="Pick">
            <span id="admin-migrate-headline"></span>
            <input id="admin-migrate-search">
            <div id="admin-migrate-target">
                <mdui-menu-item value="${ID}"></mdui-menu-item>
                <mdui-menu-item value="${OTHER}"></mdui-menu-item>
            </div>
            <input type="checkbox" id="admin-migrate-settings">
            <input type="checkbox" id="admin-migrate-getters">
            <input type="checkbox" id="admin-migrate-bindings">
            <button id="admin-migrate-cancel"></button>
            <button id="admin-migrate-submit"></button>
        </div>`);
    render();
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

beforeEach(async () => {
    history.replaceState(null, "", "/admin");
    posted = vi.fn(async () => response(204));
    confirmAnswer = true;
    window.CatapultCsrf = { postJson: posted };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())) };
    await load("channel-page", "admin-tabs", "admin-members");
});

describe("admin-members", () => {
    it("toggles a member's bot without re-rendering", async () => {
        page();
        const sw = document.querySelector(".admin-member-bot-toggle");
        sw.checked = false;
        sw.dispatchEvent(new Event("change"));
        await flush();

        expect(posts()).toEqual([[`/admin/members/${ID}/bot/toggle`, {}]]);
        expect(refreshed()).toBe(0);
    });

    it("puts the bot switch back when the toggle fails", async () => {
        posted.mockResolvedValue(response(403));
        page();
        const sw = document.querySelector(".admin-member-bot-toggle");
        sw.checked = false;
        sw.dispatchEvent(new Event("change"));
        await flush();

        expect(sw.checked).toBe(true);
    });

    it("posts a confirmed action, then re-renders the page", async () => {
        page();
        await click('[data-action="delete"]');

        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete enimaloc?" }));
        expect(posts()).toEqual([[`/admin/members/${ID}/delete`, {}]]);
        expect(refreshed()).toBe(1);
    });

    it("sends nothing when the admin cancels", async () => {
        confirmAnswer = false;
        page();
        await click('[data-action="steam/unlink"]');

        expect(posts()).toEqual([]);
        expect(refreshed()).toBe(0);
    });

    it("migrates through its dialog, only once a target and a part are picked", async () => {
        page();
        await click('[data-action="migrate"]');
        const dialog = document.getElementById("admin-migrate-dialog");

        expect(dialog.open).toBe(true);
        expect(document.getElementById("admin-migrate-headline").textContent).toBe("Migrate the data of enimaloc");
        expect(document.querySelector(`#admin-migrate-target [value="${ID}"]`).disabled).toBe(true);

        await click("#admin-migrate-submit");
        expect(posts()).toEqual([]);

        document.getElementById("admin-migrate-target").value = OTHER;
        document.getElementById("admin-migrate-bindings").checked = true;
        await click("#admin-migrate-submit");

        expect(posts()).toEqual([[`/admin/members/${ID}/migrate`,
            { targetId: OTHER, migrateSettings: false, migrateGetters: false, migrateBindings: true }]]);
        expect(dialog.open).toBe(false);
    });

    it("reloads the list as shown (same search, same page) rather than the page, inside the members tab", async () => {
        page();
        const panel = document.createElement("mdui-tab-panel");
        panel.setAttribute("value", "members");
        panel.append(...document.body.childNodes);
        document.body.append(panel);
        CatapultAdminTabs.reloadResults = vi.fn(async () => {});

        await click('[data-action="delete"]');

        expect(posts()).toEqual([[`/admin/members/${ID}/delete`, {}]]);
        expect(CatapultAdminTabs.reloadResults).toHaveBeenCalledWith(panel);
        expect(refreshed()).toBe(0);
    });

    describe("debug mode", () => {
        it("turns it on through its dialog, masked unless the secrets switch was flipped, and warns about it", async () => {
            page();
            await click('[data-action="debug-enable"]');
            const dialog = document.getElementById("admin-debug-dialog");
            const reveal = document.getElementById("admin-debug-reveal");
            const warning = document.getElementById("admin-debug-warning");

            expect(dialog.open).toBe(true);
            expect(document.getElementById("admin-debug-headline").textContent).toBe("Debug mode for enimaloc");
            expect(reveal.checked).toBe(false);
            expect(warning.hidden).toBe(true);

            reveal.checked = true;
            reveal.dispatchEvent(new Event("change"));
            expect(warning.hidden).toBe(false);

            reveal.checked = false;
            reveal.dispatchEvent(new Event("change"));
            await click("#admin-debug-submit");

            expect(posts()).toEqual([[`/admin/members/${ID}/debug/enable`, { revealSecrets: false }]]);
            expect(dialog.open).toBe(false);
            expect(refreshed()).toBe(1);
        });

        it("sends the secrets in clear when asked, and starts masked again the next time", async () => {
            page();
            await click('[data-action="debug-enable"]');
            const reveal = document.getElementById("admin-debug-reveal");
            reveal.checked = true;
            await click("#admin-debug-submit");
            await click('[data-action="debug-enable"]');

            expect(posts()).toEqual([[`/admin/members/${ID}/debug/enable`, { revealSecrets: true }]]);
            expect(reveal.checked).toBe(false);
        });

        it("keeps the dialog open when the activation is refused, and closes it on cancel", async () => {
            posted.mockResolvedValue(response(409));
            page();
            await click('[data-action="debug-enable"]');
            await click("#admin-debug-submit");
            const dialog = document.getElementById("admin-debug-dialog");

            expect(dialog.open).toBe(true);
            expect(refreshed()).toBe(0);

            await click("#admin-debug-cancel");
            expect(dialog.open).toBe(false);
        });

        it("turns it off without asking, and deletes the recording once confirmed", async () => {
            page();
            await click('[data-action="debug/disable"]');
            confirmAnswer = false;
            await click('[data-action="debug/clear"]');
            expect(posts()).toEqual([[`/admin/members/${ID}/debug/disable`, {}]]);

            confirmAnswer = true;
            await click('[data-action="debug/clear"]');
            expect(posts()).toEqual([[`/admin/members/${ID}/debug/disable`, {}], [`/admin/members/${ID}/debug/clear`, {}]]);
        });
    });

    describe("migrate target search", () => {
        const typeSearch = async query => {
            vi.useFakeTimers();
            try {
                const search = document.getElementById("admin-migrate-search");
                search.value = query;
                search.dispatchEvent(new Event("input"));
                await vi.advanceTimersByTimeAsync(300);
            } finally {
                vi.useRealTimers();
            }
            await flush();
        };

        it("replaces the target options by the members found, 300 ms after the last key", async () => {
            const fetched = vi.fn(async () => response(200, [{ id: OTHER, login: "bob" }, { id: ID, login: "enimaloc" }]));
            vi.stubGlobal("fetch", fetched);
            page();
            await click('[data-action="migrate"]');

            await typeSearch("b&o");

            expect(fetched).toHaveBeenCalledWith("/spa/admin/members/search?q=b%26o");
            const items = [...document.querySelectorAll("#admin-migrate-target mdui-menu-item")];
            expect(items.map(item => item.textContent)).toEqual(["bob", "enimaloc"]);
            expect(items.map(item => item.disabled)).toEqual([false, true]);
        });

        it("puts the page's members back when the search is cleared", async () => {
            vi.stubGlobal("fetch", vi.fn(async () => response(200, [{ id: OTHER, login: "bob" }])));
            page();
            await click('[data-action="migrate"]');

            await typeSearch("bob");
            expect(document.querySelectorAll("#admin-migrate-target mdui-menu-item")).toHaveLength(1);
            await typeSearch("");

            const items = [...document.querySelectorAll("#admin-migrate-target mdui-menu-item")];
            expect(items.map(item => item.getAttribute("value"))).toEqual([ID, OTHER]);
            expect(items.map(item => item.disabled)).toEqual([true, false]);
        });

        it("drops a search of a previous open: a pending one and one in flight", async () => {
            let answer;
            const fetched = vi.fn(() => new Promise(resolve => { answer = () => resolve(response(200, [{ id: OTHER, login: "bob" }])); }));
            vi.stubGlobal("fetch", fetched);
            page();
            await click('[data-action="migrate"]');

            vi.useFakeTimers();
            const search = document.getElementById("admin-migrate-search");
            search.value = "bob";
            search.dispatchEvent(new Event("input"));
            document.querySelector('[data-action="migrate"]').click(); // reopened before the delay ran out
            await vi.advanceTimersByTimeAsync(300);
            vi.useRealTimers();
            expect(fetched).not.toHaveBeenCalled();

            await typeSearch("bob"); // in flight...
            document.querySelector('[data-action="migrate"]').click(); // ...when reopened
            answer();
            await flush();

            expect(document.querySelectorAll("#admin-migrate-target mdui-menu-item")).toHaveLength(2);
        });

        it("keeps the options when the search fails", async () => {
            const fetched = vi.fn(async () => response(503));
            vi.stubGlobal("fetch", fetched);
            page();
            await click('[data-action="migrate"]');

            await typeSearch("bob");

            expect(document.querySelectorAll("#admin-migrate-target mdui-menu-item")).toHaveLength(2);
        });
    });

    describe("impersonate", () => {
        let assign;

        function impersonatePage() {
            html(`
                <div id="admin-members" data-confirm-text="OK" data-cancel-text="No"
                     data-label-impersonate-refused="Refused" data-label-impersonate-unavailable="Down">
                    <div class="admin-member" data-member-id="${ID}" data-member-name="alice">
                        <mdui-menu-item id="impersonate" data-impersonate="alice"></mdui-menu-item>
                        <mdui-menu-item id="self" data-impersonate="me" disabled></mdui-menu-item>
                    </div>
                </div>`);
            render();
        }

        beforeEach(() => {
            assign = vi.fn();
            vi.spyOn(window, "location", "get").mockReturnValue({ ...window.location, assign });
            window.CatapultChannel = { showError: vi.fn(), postJson: vi.fn(), refresh: vi.fn() };
        });

        afterEach(() => vi.restoreAllMocks());

        it("impersonates then opens the member's channel", async () => {
            impersonatePage();
            await click("#impersonate");

            expect(posts()).toEqual([["/admin/impersonate", { username: "alice" }]]);
            expect(assign).toHaveBeenCalledWith("/channel/alice");
        });

        it("tells a refusal and stays on the tab", async () => {
            impersonatePage();
            posted.mockResolvedValue(response(403));
            await click("#impersonate");
            expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Refused");

            posted.mockResolvedValue(response(404));
            await click("#impersonate");
            expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Refused");

            posted.mockResolvedValue(response(502));
            await click("#impersonate");
            expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");

            expect(assign).not.toHaveBeenCalled();
            expect(document.getElementById("impersonate").disabled).toBe(false);
        });

        it("posts nothing for a disabled item, and once on a double click", async () => {
            impersonatePage();
            await click("#self");
            expect(posts()).toEqual([]);

            let release;
            posted.mockImplementation(() => new Promise(resolve => { release = () => resolve(response(403)); }));
            const item = document.getElementById("impersonate");
            item.click();
            await flush();
            item.click();
            await flush();
            release();
            await flush();
            expect(posts()).toHaveLength(1);
        });
    });

    describe("targeting", () => {
        let fetched;

        const targeting = (extra = "") => `
            <div class="admin-view admin-targeting" data-member-id="${ID}" data-confirm-text="OK" data-cancel-text="No"
                 data-label-invalid="Refused" data-label-unknown="Gone" data-label-unavailable="Down"
                 data-label-flag-saved="Saved" data-label-flag-deleted="Flag gone" data-label-group-added="Joined"
                 data-label-group-removed="Left">
                <button data-admin-back></button>
                <div class="admin-targeting-row" data-flag="a/b %é">
                    <button class="admin-targeting-flag-delete" data-flag-delete="a/b %é" data-confirm="Delete a/b?"></button>
                </div>
                <div class="admin-targeting-form" id="flag-form"><input name="key"><input name="value">
                    <button class="admin-targeting-flag-save"></button></div>
                <div class="admin-targeting-row" data-group="staff">
                    <button class="admin-targeting-group-remove" data-group-id="g/1" data-confirm="Remove staff?"></button>
                </div>
                <div class="admin-targeting-form" id="group-form"><div name="groupKey"></div>
                    <button class="admin-targeting-group-save"></button></div>
                ${extra}
            </div>`;

        function listPage() {
            html(`
                <mdui-tabs id="admin-tabs" value="members">
                    <mdui-tab-panel value="members" data-admin-prerendered data-unavailable="Unavailable">
                        <div id="admin-members" data-confirm-text="OK" data-cancel-text="No">
                            <div class="admin-member" data-member-id="${ID}" data-member-name="enimaloc">
                                <mdui-menu-item id="targeting-item" data-admin-open="${ID}"></mdui-menu-item>
                                <mdui-menu-item data-action="delete" data-confirm="Delete enimaloc?"></mdui-menu-item>
                            </div>
                        </div>
                    </mdui-tab-panel>
                </mdui-tabs>`);
            document.getElementById("admin-tabs").value = "members";
            render();
        }

        function subView() {
            html(`<mdui-tabs id="admin-tabs" value="members">
                <mdui-tab-panel value="members" data-admin-prerendered data-unavailable="Unavailable">${targeting()}</mdui-tab-panel>
            </mdui-tabs>`);
            render();
            window.CatapultAdminTabs.reload = vi.fn(async () => {});
        }

        const field = (form, name) => document.querySelector(`#${form} [name="${name}"]`);
        const fill = (el, value) => { el.value = value; el.dispatchEvent(new Event("input", { bubbles: true })); };

        beforeEach(() => {
            fetched = vi.fn(async url => response(200, url.endsWith("/members")
                ? `<div id="admin-members" data-confirm-text="OK" data-cancel-text="No"><p class="view">list</p></div>`
                : targeting()));
            window.fetch = fetched;
            window.CatapultChannel = { showError: vi.fn(), postJson: vi.fn(), refresh: vi.fn() };
            window.mdui.snackbar = vi.fn();
        });

        it("opens #members/{id} from the Targeting item and comes back", async () => {
            listPage();
            await flush();
            expect(fetched).not.toHaveBeenCalled(); // the list came with the page

            await click("#targeting-item");

            expect(fetched).toHaveBeenLastCalledWith(`/spa/admin/members/${ID}`);
            expect(location.hash).toBe(`#members/${ID}`);
            expect(document.querySelector(".admin-targeting")).not.toBeNull();

            await click("[data-admin-back]");

            expect(fetched).toHaveBeenLastCalledWith("/spa/admin/members");
            expect(location.hash).toBe("#members");
            expect(document.querySelector(".view").textContent).toBe("list");
        });

        it("opens the sub-view named in the hash when the page loads", async () => {
            history.replaceState(null, "", `/admin#members/${ID}`);
            listPage();
            await flush();

            expect(fetched).toHaveBeenCalledWith(`/spa/admin/members/${ID}`);
        });

        it("adds and deletes a flag, adds and removes a group, then reloads the sub-view", async () => {
            subView();

            fill(field("flag-form", "key"), "  tier ");
            fill(field("flag-form", "value"), "2");
            await click(".admin-targeting-flag-save");
            expect(posts()).toEqual([[`/admin/members/${ID}/flags`, { key: "tier", value: "2" }]]);
            expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);

            await click(".admin-targeting-flag-delete");
            expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete a/b?" }));
            expect(posts().at(-1)).toEqual([`/admin/members/${ID}/flags/delete`, { key: "a/b %é" }]);

            field("group-form", "groupKey").value = "beta";
            await click(".admin-targeting-group-save");
            expect(posts().at(-1)).toEqual([`/admin/members/${ID}/groups`, { groupKey: "beta" }]);

            await click(".admin-targeting-group-remove");
            expect(posts().at(-1)).toEqual([`/admin/members/${ID}/groups/g%2F1/delete`, {}]);
            expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(4);
        });

        it("sends an empty value as none, and refuses a missing key or group without posting", async () => {
            subView();

            await click(".admin-targeting-flag-save");
            await click(".admin-targeting-group-save");
            expect(posts()).toEqual([]);
            expect(field("flag-form", "key").hasAttribute("invalid")).toBe(true);
            expect(field("group-form", "groupKey").hasAttribute("invalid")).toBe(true);

            fill(field("flag-form", "key"), "beta");
            await click(".admin-targeting-flag-save");
            expect(posts()).toEqual([[`/admin/members/${ID}/flags`, { key: "beta", value: null }]]);
        });

        it("posts nothing when the admin cancels a deletion", async () => {
            confirmAnswer = false;
            subView();

            await click(".admin-targeting-flag-delete");
            await click(".admin-targeting-group-remove");

            expect(posts()).toEqual([]);
        });

        it("tells a refusal by its status, and reloads when the member or group is gone", async () => {
            subView();
            fill(field("flag-form", "key"), "beta");

            posted.mockResolvedValue(response(400));
            await click(".admin-targeting-flag-save");
            expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Refused");
            expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();

            posted.mockResolvedValue(response(404));
            await click(".admin-targeting-flag-save");
            expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");
            expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);

            posted.mockResolvedValue(response(502));
            await click(".admin-targeting-flag-save");
            expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        });

        it("disables an action's button while it runs: a double click posts once", async () => {
            subView();
            fill(field("flag-form", "key"), "beta");
            let release;
            posted.mockImplementation(() => new Promise(resolve => { release = () => resolve(response(204)); }));

            const button = document.querySelector(".admin-targeting-flag-save");
            button.click();
            await flush();
            expect(button.disabled).toBe(true);
            button.click();
            await flush();
            release();
            await flush();

            expect(posts()).toHaveLength(1);
            expect(button.disabled).toBe(false);
        });
    });
});
