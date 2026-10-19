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
            </div>
        </div>
        <div id="admin-migrate-dialog" data-headline="Migrate the data of {0}" data-incomplete="Pick">
            <span id="admin-migrate-headline"></span>
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
