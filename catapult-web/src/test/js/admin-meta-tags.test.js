/*
 * The admin Meta tab's meta tags: the form adds one, a row's edit button loads it into the form
 * (saved in place, or cancelled back to adding), its delete button removes it once confirmed; a
 * refusal is told by its status and leaves the tab as it is.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;

const form = () => document.querySelector(".admin-meta-tags-form");
const field = name => form().querySelector(`[name="${name}"]`);
const save = () => document.querySelector(".admin-meta-tag-save");
const cancel = () => document.querySelector(".admin-meta-tag-cancel");

function page() {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="meta">
            <div class="admin-view admin-well-known">
                <section class="admin-meta-tags" data-confirm-text="OK" data-cancel-text="No"
                         data-error="Failed ({0})" data-error-400="Invalid" data-error-404="Gone" data-error-409="Taken">
                    <div class="admin-meta-tag-row" data-id="5" data-attribute="PROPERTY" data-key="og:image" data-content="https://x/y.png">
                        <button class="admin-meta-tag-edit"></button>
                        <button class="admin-meta-tag-delete" data-confirm="Delete og:image?"></button>
                    </div>
                    <div class="admin-meta-tags-form" data-label-add="Add" data-label-save="Save">
                        <select name="attribute"><option value="NAME">name</option><option value="PROPERTY">property</option></select>
                        <input name="key">
                        <mdui-text-field><input name="content"></mdui-text-field>
                        <button class="admin-meta-tag-cancel" hidden>Cancel</button>
                        <button class="admin-meta-tag-save">Add</button>
                    </div>
                </section>
            </div>
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
    await load("admin-tabs", "admin-meta-tags");
    window.CatapultChannel = { showError: vi.fn() }; // after load: channel-page.js isn't loaded, nothing overrides it
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

describe("admin-meta-tags", () => {
    it("adds a tag from the form, trimmed, then reloads the tab", async () => {
        field("key").value = " google-site-verification ";
        field("content").value = " abc123 ";

        await click(save());

        expect(posted).toHaveBeenCalledWith("/admin/meta/tags", { attribute: "NAME", key: "google-site-verification", content: "abc123" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalledWith(document.querySelector('mdui-tab-panel[value="meta"]'));
    });

    it("loads a row into the form, saves it in place, and cancels back to adding", async () => {
        await click(".admin-meta-tag-edit");
        expect(field("attribute").value).toBe("PROPERTY");
        expect(field("key").value).toBe("og:image");
        expect(field("content").value).toBe("https://x/y.png");
        expect(save().textContent).toBe("Save");
        expect(cancel().hidden).toBe(false);

        field("content").value = "https://x/z.png";
        await click(save());
        expect(posted).toHaveBeenCalledWith("/admin/meta/tags/5", { attribute: "PROPERTY", key: "og:image", content: "https://x/z.png" });

        await click(cancel());
        expect(field("key").value).toBe("");
        expect(field("attribute").value).toBe("NAME");
        expect(save().textContent).toBe("Add");
        expect(cancel().hidden).toBe(true);
        expect(form().dataset.id).toBeUndefined();
    });

    it("deletes a tag once confirmed, not otherwise", async () => {
        confirmAnswer = false;
        await click(".admin-meta-tag-delete");
        expect(posted).not.toHaveBeenCalled();

        confirmAnswer = true;
        await click(".admin-meta-tag-delete");
        expect(posted).toHaveBeenCalledWith("/admin/meta/tags/5/delete", {});
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("tells a refusal by its status and keeps the tab and the form as they are", async () => {
        posted.mockResolvedValueOnce(response(409)).mockResolvedValueOnce(response(500)).mockRejectedValueOnce(new Error("offline"));
        field("key").value = "theme-color";

        await click(save());
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Taken");
        await click(save());
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Failed (500)");
        await click(save());
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Failed (—)");

        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
        expect(field("key").value).toBe("theme-color");
        expect(save().disabled).toBe(false);
    });

    it("reloads the tab when the tag edited is already gone", async () => {
        posted.mockResolvedValueOnce(response(404));
        await click(".admin-meta-tag-edit");
        await click(save());
        expect(CatapultChannel.showError).toHaveBeenCalledWith("Gone");
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("saves with Enter in a field", async () => {
        field("key").value = "author";
        field("content").value = "Jane";
        field("content").dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
        await flush();
        expect(posted).toHaveBeenCalledWith("/admin/meta/tags", { attribute: "NAME", key: "author", content: "Jane" });
    });

    it("doesn't save on the Enter that confirms an IME's conversion", async () => {
        field("key").value = "author";
        field("content").dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", isComposing: true, bubbles: true }));
        field("content").dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", keyCode: 229, bubbles: true }));
        await flush();
        expect(posted).not.toHaveBeenCalled();
    });

    it("asks once for a delete clicked twice before the confirmation shows", async () => {
        let answer;
        mdui.confirm.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
        const button = document.querySelector(".admin-meta-tag-delete");
        button.click();
        button.click();
        await flush();
        expect(mdui.confirm).toHaveBeenCalledTimes(1);

        answer();
        await flush();
        expect(posted).toHaveBeenCalledTimes(1);
    });

    it("lets the delete be clicked again after the confirmation is refused", async () => {
        confirmAnswer = false;
        await click(".admin-meta-tag-delete");
        expect(document.querySelector(".admin-meta-tag-delete").disabled).toBe(false);
    });
});
