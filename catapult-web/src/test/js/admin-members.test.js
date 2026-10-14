/*
 * The admin members tab: the bot switch only posts, every other action posts once confirmed and
 * then re-renders the page; "migrate" goes through its dialog instead.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
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
    await load("channel-page", "admin-members");
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
});
