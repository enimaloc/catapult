/*
 * The admin rate limits tab: a learned limit corrected in the dialog filled from its row (an empty field left to the
 * learning, no scope for a host), or reset once confirmed; the list patched after.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render } from "./helpers.js";

let tabs;
let sent;
let confirmAnswer;

function list() {
    html(`
        <mdui-tab-panel value="rate-limits">
            <div class="admin-view admin-rate-limits" data-saved="Saved." data-reset-confirm="Reset?" data-reset-description="Forgets">
                <div class="admin-row" data-id="HOST store.steampowered.com" data-host="true" data-calls="20" data-window="60" data-resume="">
                    <button class="admin-rl-edit" id="edit-host"></button>
                    <button class="admin-rl-reset" id="reset-host"></button>
                </div>
                <div class="admin-row" data-id="GET store.steampowered.com/app/{id}" data-host="false" data-scope="ROUTE"
                     data-scope-pinned="true" data-calls="" data-window="" data-resume="30">
                    <button class="admin-rl-edit" id="edit-route"></button>
                </div>
                <mdui-dialog id="admin-rl-dialog">
                    <p class="admin-rl-dialog-id"><code></code></p>
                    <mdui-text-field name="calls"></mdui-text-field>
                    <mdui-select name="window"></mdui-select>
                    <mdui-text-field name="resume"></mdui-text-field>
                    <mdui-select name="scope"></mdui-select>
                    <button class="admin-dialog-close"></button>
                    <button class="admin-rl-save"></button>
                </mdui-dialog>
            </div>
        </mdui-tab-panel>`);
    render();
}

const panel = () => document.querySelector('mdui-tab-panel[value="rate-limits"]');
const dialog = () => document.getElementById("admin-rl-dialog");
const field = name => dialog().querySelector(`[name="${name}"]`);
const tick = () => new Promise(resolve => setTimeout(resolve, 0));

async function click(selector) {
    document.querySelector(selector).click();
    await tick();
}

beforeEach(async () => {
    confirmAnswer = true;
    sent = "ok";
    tabs = {
        post: vi.fn(async () => sent),
        confirmed: vi.fn(async () => confirmAnswer),
        patch: vi.fn(async () => {})
    };
    await load("channel-page", "admin-rate-limits");
    window.CatapultAdminTabs = tabs;
    window.mdui = { ...(window.mdui ?? {}), snackbar: vi.fn() };
});

describe("admin-rate-limits", () => {
    it("corrects a host's limit from its row, without a scope", async () => {
        list();
        await click("#edit-host");

        expect(dialog().open).toBe(true);
        expect(dialog().querySelector("code").textContent).toBe("HOST store.steampowered.com");
        expect(field("calls").value).toBe("20");
        expect(field("scope").hidden).toBe(true);

        field("calls").value = "25";
        field("window").value = "300";
        field("resume").value = "45";
        await click(".admin-rl-save");

        const [, , url, body] = tabs.post.mock.calls[0];
        expect(url).toBe("/admin/rate-limits/correct?id=HOST+store.steampowered.com");
        expect(body).toEqual({ limitCalls: 25, limitWindowSeconds: 300, resumeSeconds: 45, scope: null });
        expect(dialog().open).toBe(false);
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Saved." });
        expect(tabs.patch).toHaveBeenCalledWith(panel());
    });

    it("leaves an empty limit to the learning, and keeps a route's chosen scope", async () => {
        list();
        await click("#edit-route");

        expect(field("window").value).toBe("60");
        expect(field("scope").hidden).toBe(false);
        expect(field("scope").value).toBe("ROUTE");
        await click(".admin-rl-save");

        expect(tabs.post.mock.calls[0][3]).toEqual({ limitCalls: null, limitWindowSeconds: null, resumeSeconds: 30, scope: "ROUTE" });
    });

    it("gives a route's scope back to the learning when none was chosen", async () => {
        list();
        document.querySelector('[data-id^="GET"]').dataset.scopePinned = "false";
        await click("#edit-route");
        expect(field("scope").value).toBe("LEARNED");
        await click(".admin-rl-save");
        expect(tabs.post.mock.calls[0][3].scope).toBe("LEARNED");
    });

    it("keeps the dialog open when the correction is refused, and closes it on demand", async () => {
        list();
        await click("#edit-host");
        sent = 400;
        await click(".admin-rl-save");
        expect(dialog().open).toBe(true);
        expect(tabs.patch).not.toHaveBeenCalled();

        await click(".admin-dialog-close");
        expect(dialog().open).toBe(false);
    });

    it("resets a limit once confirmed, then patches the list", async () => {
        list();
        confirmAnswer = false;
        await click("#reset-host");
        expect(tabs.post).not.toHaveBeenCalled();

        confirmAnswer = true;
        await click("#reset-host");
        expect(tabs.confirmed).toHaveBeenCalledWith(expect.anything(), "Reset?", "Forgets");
        expect(tabs.post.mock.calls[0][2]).toBe("/admin/rate-limits/reset?id=HOST+store.steampowered.com");
        expect(tabs.patch).toHaveBeenCalledTimes(1);

        sent = 404;
        await click("#reset-host");
        expect(tabs.patch).toHaveBeenCalledTimes(2);
        sent = 502;
        await click("#reset-host");
        expect(tabs.patch).toHaveBeenCalledTimes(2);
    });
});
