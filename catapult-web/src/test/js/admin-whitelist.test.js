/*
 * The admin Whitelist tab: the two switches, adding and deleting an entry, the invitation settings
 * and an invite's quota dialog; every refusal is told by its status.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;

const add = () => document.querySelector('.admin-whitelist-add [name="twitchId"]');
const settings = name => document.querySelector(`.admin-whitelist-settings [name="${name}"]`);
const dialog = () => document.getElementById("admin-whitelist-quota-dialog");
const quota = name => dialog().querySelector(`[name="${name}"]`);

function page() {
    html(`
        <mdui-tab-panel value="whitelist">
            <div class="admin-view admin-whitelist" data-confirm-text="OK" data-cancel-text="No"
                 data-label-invalid="Refused" data-label-unknown="Gone" data-label-unavailable="Down"
                 data-label-added="Added" data-label-deleted="Deleted" data-label-toggled="Toggled"
                 data-label-invites-toggled="Invites toggled" data-label-settings-saved="Settings saved"
                 data-label-quota-saved="Quota saved">
                <button data-whitelist-toggle></button>
                <div class="admin-whitelist-add"><input name="twitchId"><button class="admin-whitelist-add-save"></button></div>
                <button data-invites-toggle></button>
                <div class="admin-whitelist-settings">
                    <input name="globalMaxMembers"><input name="defaultMaxUses">
                    <input type="checkbox" name="defaultCanReinvite">
                    <button class="admin-whitelist-settings-save"></button>
                </div>
                <div class="admin-row" data-invite="i-1">
                    <button class="admin-whitelist-quota" data-quota="i/1" data-max-uses="4" data-can-reinvite="false"></button>
                </div>
                <button class="admin-whitelist-delete" data-delete="old user/1" data-confirm="Remove 123?"></button>
                <div id="admin-whitelist-quota-dialog">
                    <div class="admin-whitelist-form">
                        <input name="maxUses"><input name="canReinvite">
                        <button class="admin-dialog-close"></button>
                        <button class="admin-whitelist-quota-save"></button>
                    </div>
                </div>
            </div>
        </mdui-tab-panel>`);
    render();
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

const fill = (field, value) => {
    field.value = value;
    field.dispatchEvent(new Event("input", { bubbles: true }));
};

beforeEach(async () => {
    confirmAnswer = true;
    posted = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())), snackbar: vi.fn() };
    await load("admin-tabs", "admin-whitelist");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

describe("admin-whitelist", () => {
    it("toggles the whitelist and the invitations, then reloads", async () => {
        await click("[data-whitelist-toggle]");
        expect(posted).toHaveBeenLastCalledWith("/admin/whitelist/toggle", {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Toggled" });

        await click("[data-invites-toggle]");
        expect(posted).toHaveBeenLastCalledWith("/admin/whitelist/invite/toggle", {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Invites toggled" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(2);
    });

    it("adds and deletes an entry; saves settings and a quota from its dialog", async () => {
        fill(add(), " 123 ");
        await click(".admin-whitelist-add-save");
        expect(posted).toHaveBeenLastCalledWith("/admin/whitelist/add", { twitchId: "123" });

        await click("[data-delete]");
        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Remove 123?" }));
        expect(posted).toHaveBeenLastCalledWith("/admin/whitelist/delete", { twitchId: "old user/1" });

        fill(settings("globalMaxMembers"), "");
        fill(settings("defaultMaxUses"), "3");
        settings("defaultCanReinvite").checked = true;
        await click(".admin-whitelist-settings-save");
        expect(posted).toHaveBeenLastCalledWith("/admin/whitelist/invite/settings",
            { globalMaxMembers: null, defaultMaxUses: 3, defaultCanReinvite: true });

        await click("[data-quota]");
        expect(dialog().open).toBe(true);
        expect(quota("maxUses").value).toBe("4");
        expect(quota("canReinvite").value).toBe("false");
        fill(quota("maxUses"), "");
        fill(quota("canReinvite"), "true");
        await click(".admin-whitelist-quota-save");
        expect(posted).toHaveBeenLastCalledWith("/admin/whitelist/invite/i%2F1/quota", { maxUses: null, canReinvite: true });
        expect(dialog().open).toBe(false);
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(4);
    });

    it("sends a quota's empty reinvite choice as null", async () => {
        await click("[data-quota]");
        fill(quota("maxUses"), "0");
        fill(quota("canReinvite"), "");
        await click(".admin-whitelist-quota-save");

        expect(posted).toHaveBeenLastCalledWith("/admin/whitelist/invite/i%2F1/quota", { maxUses: 0, canReinvite: null });
    });

    it("refuses a non-numeric Twitch id and bad numbers, without posting", async () => {
        fill(add(), "abc");
        await click(".admin-whitelist-add-save");
        expect(add().hasAttribute("invalid")).toBe(true);

        fill(settings("globalMaxMembers"), "0");
        fill(settings("defaultMaxUses"), "-1");
        await click(".admin-whitelist-settings-save");
        expect(settings("globalMaxMembers").hasAttribute("invalid")).toBe(true);
        expect(settings("defaultMaxUses").hasAttribute("invalid")).toBe(true);

        await click("[data-quota]");
        fill(quota("maxUses"), "1.5");
        await click(".admin-whitelist-quota-save");
        expect(quota("maxUses").hasAttribute("invalid")).toBe(true);
        expect(dialog().open).toBe(true);
        expect(posted).not.toHaveBeenCalled();
    });

    it("tells a refusal by its status, and reloads when the invite is gone", async () => {
        fill(add(), "123");
        posted.mockResolvedValueOnce(response(400));
        await click(".admin-whitelist-add-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Refused");

        posted.mockResolvedValueOnce(response(502));
        await click("[data-whitelist-toggle]");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();

        posted.mockResolvedValueOnce(response(404));
        await click("[data-quota]");
        await click(".admin-whitelist-quota-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);
        expect(dialog().open).toBe(false);
    });

    it("does not delete when the confirmation is declined", async () => {
        confirmAnswer = false;
        await click("[data-delete]");

        expect(posted).not.toHaveBeenCalled();
        expect(document.querySelector("[data-delete]").disabled).toBe(false);
    });

    it("sends one request on a double click of the quota save", async () => {
        let release;
        posted.mockImplementation(() => new Promise(resolve => { release = () => resolve(response(204)); }));
        await click("[data-quota]");
        const button = document.querySelector(".admin-whitelist-quota-save");
        button.click();
        button.click();
        await flush();

        expect(posted).toHaveBeenCalledTimes(1);
        expect(button.disabled).toBe(true);
        release();
        await flush();
        expect(button.disabled).toBe(false);
    });

    it("sends one request on a double click of a switch", async () => {
        let release;
        posted.mockImplementation(() => new Promise(resolve => { release = () => resolve(response(204)); }));
        const button = document.querySelector("[data-whitelist-toggle]");
        button.click();
        button.click();
        await flush();

        expect(posted).toHaveBeenCalledTimes(1);
        expect(button.disabled).toBe(true);
        release();
        await flush();
    });
});
