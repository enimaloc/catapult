/*
 * The admin Groups tab: the create card needs a key and a name, Rename and Add member go through
 * their dialogs, Delete confirms with the group's name; every refusal is told by its status.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;

const create = name => document.querySelector(`.admin-groups-create [name="${name}"]`);
const renameDialog = () => document.getElementById("admin-groups-rename-dialog");
const memberDialog = () => document.getElementById("admin-groups-member-dialog");

function page() {
    html(`
        <mdui-tab-panel value="groups">
            <div class="admin-view admin-groups" data-confirm-text="OK" data-cancel-text="No"
                 data-label-invalid="Refused" data-label-conflict="Key taken" data-label-unknown="Gone"
                 data-label-member-unknown="No such member" data-label-unavailable="Down"
                 data-label-created="Created" data-label-renamed="Renamed" data-label-member-added="Added" data-label-deleted="Deleted">
                <div class="admin-groups-create">
                    <input name="key"><input name="name"><input name="description">
                    <button class="admin-groups-create-save"></button>
                </div>
                <div class="admin-row" data-id="g/1">
                    <button class="admin-groups-rename" data-rename="g/1" data-name="Beta" data-description="For testers"></button>
                    <button class="admin-groups-add-member" data-add-member="g/1"></button>
                    <button class="admin-groups-delete" data-delete="g/1" data-confirm="Delete Beta?"></button>
                </div>
                <div id="admin-groups-rename-dialog">
                    <div class="admin-groups-form">
                        <input name="name"><input name="description">
                        <button class="admin-dialog-close"></button>
                        <button class="admin-groups-rename-save"></button>
                    </div>
                </div>
                <div id="admin-groups-member-dialog">
                    <div class="admin-groups-form">
                        <input name="twitchUsername">
                        <button class="admin-dialog-close"></button>
                        <button class="admin-groups-member-save"></button>
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
    await load("admin-tabs", "admin-groups");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

describe("admin-groups", () => {
    it("creates a group from the form and reloads; key and name required", async () => {
        fill(create("key"), "  ");
        fill(create("name"), "Beta");
        await click(".admin-groups-create-save");
        expect(posted).not.toHaveBeenCalled();
        expect(create("key").hasAttribute("invalid")).toBe(true);

        fill(create("key"), " beta ");
        fill(create("name"), " ");
        await click(".admin-groups-create-save");
        expect(posted).not.toHaveBeenCalled();
        expect(create("name").hasAttribute("invalid")).toBe(true);

        fill(create("name"), " Beta ");
        fill(create("description"), "For testers");
        await click(".admin-groups-create-save");

        expect(posted).toHaveBeenCalledWith("/admin/groups", { key: "beta", name: "Beta", description: "For testers" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("sends an empty description as null", async () => {
        fill(create("key"), "beta");
        fill(create("name"), "Beta");
        await click(".admin-groups-create-save");

        expect(posted).toHaveBeenCalledWith("/admin/groups", { key: "beta", name: "Beta", description: null });
    });

    it("tells a duplicate key and an unreachable catapult-api, without reloading", async () => {
        fill(create("key"), "beta");
        fill(create("name"), "Beta");
        posted.mockResolvedValueOnce(response(409));
        await click(".admin-groups-create-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Key taken");

        posted.mockResolvedValueOnce(response(502));
        await click(".admin-groups-create-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
    });

    it("renames through its dialog, prefilled with the group's name and description", async () => {
        await click("[data-rename]");
        const dialog = renameDialog();
        expect(dialog.open).toBe(true);
        expect(dialog.querySelector('[name="name"]').value).toBe("Beta");
        expect(dialog.querySelector('[name="description"]').value).toBe("For testers");

        fill(dialog.querySelector('[name="name"]'), " Gamma ");
        fill(dialog.querySelector('[name="description"]'), "");
        await click(".admin-groups-rename-save");

        expect(posted).toHaveBeenCalledWith("/admin/groups/g%2F1/rename", { name: "Gamma", description: null });
        expect(dialog.open).toBe(false);
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("refuses an empty name when renaming", async () => {
        await click("[data-rename]");
        fill(renameDialog().querySelector('[name="name"]'), " ");
        await click(".admin-groups-rename-save");

        expect(posted).not.toHaveBeenCalled();
        expect(renameDialog().open).toBe(true);
    });

    it("adds a member by Twitch username through its dialog", async () => {
        await click("[data-add-member]");
        expect(memberDialog().open).toBe(true);
        fill(memberDialog().querySelector('[name="twitchUsername"]'), " alice ");
        await click(".admin-groups-member-save");

        expect(posted).toHaveBeenCalledWith("/admin/groups/g%2F1/members", { twitchUsername: "alice" });
        expect(memberDialog().open).toBe(false);
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Added" });
    });

    it("tells an unknown login and keeps the dialog open", async () => {
        posted.mockResolvedValue(response(404));
        await click("[data-add-member]");
        fill(memberDialog().querySelector('[name="twitchUsername"]'), "nobody");
        await click(".admin-groups-member-save");

        expect(CatapultChannel.showError).toHaveBeenCalledWith("No such member");
        expect(memberDialog().open).toBe(true);
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
    });

    it("tells a group deleted meanwhile (404 on rename) and reloads", async () => {
        posted.mockResolvedValue(response(404));
        await click("[data-rename]");
        await click(".admin-groups-rename-save");

        expect(CatapultChannel.showError).toHaveBeenCalledWith("Gone");
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("confirms deletion with the group's name, the button disabled meanwhile", async () => {
        let release;
        posted.mockImplementationOnce(() => new Promise(resolve => { release = () => resolve(response(204)); }));
        const button = document.querySelector("[data-delete]");
        button.click();
        button.click();
        await flush();

        expect(mdui.confirm).toHaveBeenCalledTimes(1);
        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete Beta?" }));
        expect(button.disabled).toBe(true);
        expect(posted).toHaveBeenCalledWith("/admin/groups/g%2F1/delete", {});
        release();
        await flush();
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("does not delete when the confirmation is declined", async () => {
        confirmAnswer = false;
        await click("[data-delete]");

        expect(posted).not.toHaveBeenCalled();
        expect(document.querySelector("[data-delete]").disabled).toBe(false);
    });

    it("sends one request on a double click of Create", async () => {
        let release;
        posted.mockImplementation(() => new Promise(resolve => { release = () => resolve(response(201)); }));
        fill(create("key"), "beta");
        fill(create("name"), "Beta");
        const button = document.querySelector(".admin-groups-create-save");
        button.click();
        button.click();
        await flush();

        expect(posted).toHaveBeenCalledTimes(1);
        expect(button.disabled).toBe(true);
        release();
        await flush();
    });
});
