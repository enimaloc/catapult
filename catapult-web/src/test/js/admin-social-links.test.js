/*
 * The admin Links tab's social links: the form adds one, a row's edit button loads it into the form
 * (saved in place, or cancelled back to adding), its delete button removes it once confirmed and its
 * arrows send the whole order; a success patches the tab (and empties the form back to adding), a
 * refusal is told by its status and leaves the tab as it is.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;

const form = () => document.querySelector(".admin-social-links-form");
const field = name => form().querySelector(`[name="${name}"]`);
const save = () => document.querySelector(".admin-social-link-save");
const cancel = () => document.querySelector(".admin-social-link-cancel");
const row = id => document.querySelector(`.admin-social-link-row[data-id="${id}"]`);

function page() {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="links">
            <section class="admin-social-links" data-confirm-text="OK" data-cancel-text="No"
                     data-error="Failed ({0})" data-error-400="Invalid" data-error-404="Gone">
                <div class="admin-social-link-row" data-id="5" data-platform="GITHUB" data-label="Code" data-url="https://github.com/x" data-visible="true">
                    <button class="admin-social-link-up"></button>
                    <button class="admin-social-link-down"></button>
                    <button class="admin-social-link-edit"></button>
                    <button class="admin-social-link-delete" data-confirm="Delete Code?"></button>
                </div>
                <div class="admin-social-link-row" data-id="6" data-platform="OTHER" data-label="" data-url="https://example.org" data-visible="false">
                    <button class="admin-social-link-up"></button>
                    <button class="admin-social-link-down"></button>
                    <button class="admin-social-link-edit"></button>
                    <button class="admin-social-link-delete" data-confirm="Delete other?"></button>
                </div>
                <div class="admin-social-link-row" data-id="7" data-platform="X" data-label="" data-url="https://x.com/y" data-visible="true">
                    <button class="admin-social-link-up"></button>
                    <button class="admin-social-link-down"></button>
                    <button class="admin-social-link-edit"></button>
                    <button class="admin-social-link-delete" data-confirm="Delete x?"></button>
                </div>
                <div class="admin-social-links-form" data-label-add="Add" data-label-save="Save">
                    <select name="platform"><option value="OTHER">other</option><option value="GITHUB">github</option><option value="X">x</option></select>
                    <input name="label">
                    <input name="url">
                    <input type="checkbox" name="visible" checked>
                    <button class="admin-social-link-cancel" hidden>Cancel</button>
                    <button class="admin-social-link-save">Add</button>
                </div>
            </section>
        </mdui-tab-panel>`);
    render();
}

async function click(target) {
    (typeof target === "string" ? document.querySelector(target) : target).click();
    await flush();
}

beforeEach(async () => {
    posted = vi.fn(async () => response(201));
    confirmAnswer = true;
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())) };
    await load("admin-tabs", "admin-social-links");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.patch = vi.fn(async () => {});
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

describe("admin-social-links", () => {
    it("adds a link from the form, trimmed, then patches the tab and empties the form", async () => {
        field("platform").value = "X";
        field("label").value = " Follow ";
        field("url").value = " https://x.com/me ";
        field("visible").checked = false;

        await click(save());

        expect(posted).toHaveBeenCalledWith("/admin/social-links",
                { platform: "X", label: "Follow", url: "https://x.com/me", visible: false });
        expect(CatapultAdminTabs.patch).toHaveBeenCalledWith(document.querySelector('mdui-tab-panel[value="links"]'));
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
        expect(field("platform").value).toBe("OTHER");
        expect(field("label").value).toBe("");
        expect(field("url").value).toBe("");
        expect(field("visible").checked).toBe(true);
    });

    it("loads a row into the form, saves it in place, and cancels back to adding", async () => {
        await click(row(6).querySelector(".admin-social-link-edit"));
        expect(field("platform").value).toBe("OTHER");
        expect(field("url").value).toBe("https://example.org");
        expect(field("visible").checked).toBe(false);
        expect(save().textContent).toBe("Save");
        expect(cancel().hidden).toBe(false);

        field("url").value = "https://example.org/new";
        await click(save());
        expect(posted).toHaveBeenCalledWith("/admin/social-links/6",
                { platform: "OTHER", label: "", url: "https://example.org/new", visible: false });
        expect(CatapultAdminTabs.patch).toHaveBeenCalled();
        expect(form().dataset.id).toBeUndefined(); // saved: back to adding
        expect(save().textContent).toBe("Add");

        await click(row(5).querySelector(".admin-social-link-edit"));
        expect(field("url").value).toBe("https://github.com/x");
        await click(cancel());
        expect(field("url").value).toBe("");
        expect(field("platform").value).toBe("OTHER");
        expect(field("visible").checked).toBe(true);
        expect(save().textContent).toBe("Add");
        expect(cancel().hidden).toBe(true);
        expect(form().dataset.id).toBeUndefined();
    });

    it("deletes a link once confirmed, not otherwise", async () => {
        confirmAnswer = false;
        await click(row(5).querySelector(".admin-social-link-delete"));
        expect(posted).not.toHaveBeenCalled();
        expect(row(5).querySelector(".admin-social-link-delete").disabled).toBe(false);

        confirmAnswer = true;
        await click(row(5).querySelector(".admin-social-link-delete"));
        expect(posted).toHaveBeenCalledWith("/admin/social-links/5/delete", {});
        expect(CatapultAdminTabs.patch).toHaveBeenCalled();
    });

    it("empties the form back to adding when the link it edits is deleted, not another one", async () => {
        await click(row(6).querySelector(".admin-social-link-edit"));
        await click(row(5).querySelector(".admin-social-link-delete"));
        expect(form().dataset.id).toBe("6");

        await click(row(6).querySelector(".admin-social-link-delete"));
        expect(form().dataset.id).toBeUndefined();
        expect(field("url").value).toBe("");
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(2);
    });

    it("asks once for a delete clicked twice before the confirmation shows", async () => {
        let answer;
        mdui.confirm.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
        const button = row(5).querySelector(".admin-social-link-delete");
        button.click();
        button.click();
        await flush();
        expect(mdui.confirm).toHaveBeenCalledTimes(1);

        answer();
        await flush();
        expect(posted).toHaveBeenCalledTimes(1);
    });

    it("sends the whole order when a link goes up or down, then patches the tab", async () => {
        await click(row(7).querySelector(".admin-social-link-up"));
        expect(posted).toHaveBeenLastCalledWith("/admin/social-links/order", { ids: [5, 7, 6] });

        await click(row(5).querySelector(".admin-social-link-down"));
        expect(posted).toHaveBeenLastCalledWith("/admin/social-links/order", { ids: [6, 5, 7] });
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(2);
    });

    it("sends nothing for the first link going up or the last going down", async () => {
        await click(row(5).querySelector(".admin-social-link-up"));
        await click(row(7).querySelector(".admin-social-link-down"));
        expect(posted).not.toHaveBeenCalled();
    });

    it("tells a refusal by its status and keeps the tab and the form as they are", async () => {
        posted.mockResolvedValueOnce(response(400)).mockResolvedValueOnce(response(500)).mockRejectedValueOnce(new Error("offline"));
        field("url").value = "javascript:alert(1)";

        await click(save());
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Invalid");
        await click(save());
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Failed (500)");
        await click(save());
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Failed (—)");

        expect(CatapultAdminTabs.patch).not.toHaveBeenCalled();
        expect(field("url").value).toBe("javascript:alert(1)");
        expect(save().disabled).toBe(false);
    });

    it("tells a refused order and patches the tab so the list shows the real order", async () => {
        posted.mockResolvedValueOnce(response(400));
        await click(row(7).querySelector(".admin-social-link-up"));
        expect(CatapultChannel.showError).toHaveBeenCalledWith("Invalid");
        expect(CatapultAdminTabs.patch).toHaveBeenCalled();
    });

    it("patches the tab when the link edited is already gone", async () => {
        posted.mockResolvedValueOnce(response(404));
        await click(row(5).querySelector(".admin-social-link-edit"));
        await click(save());
        expect(CatapultChannel.showError).toHaveBeenCalledWith("Gone");
        expect(CatapultAdminTabs.patch).toHaveBeenCalled();
    });

    it("saves with Enter in a field, except the one confirming an IME's conversion", async () => {
        field("url").value = "https://x.com/me";
        field("url").dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", isComposing: true, bubbles: true }));
        field("url").dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", keyCode: 229, bubbles: true }));
        await flush();
        expect(posted).not.toHaveBeenCalled();

        field("url").dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
        await flush();
        expect(posted).toHaveBeenCalledWith("/admin/social-links",
                { platform: "OTHER", label: "", url: "https://x.com/me", visible: true });
    });
});
