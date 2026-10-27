/*
 * The admin Keys tab: adding a key (400 says the card's invalid-format text), the double click that
 * posts once, refreshing and deleting.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;

const field = provider => document.querySelector(`[data-provider="${provider}"] [name="apiKey"]`);
const card = provider => document.querySelector(`[data-provider="${provider}"]`);

function page() {
    html(`
        <mdui-tab-panel value="keys">
            <div class="admin-view admin-keys" data-confirm-text="OK" data-cancel-text="No"
                 data-label-unknown="Gone" data-label-unavailable="Down" data-label-added="Added"
                 data-label-deleted="Deleted" data-label-refreshed="Refreshed">
                <div class="admin-keys-card" data-provider="steam" data-label-invalid="Invalid Steam key">
                    <button class="admin-keys-refresh"></button>
                    <input name="apiKey"><button class="admin-keys-add-save"></button>
                    <button class="admin-keys-delete" data-key-id="id/1 é" data-confirm="Delete abcd…wxyz?"></button>
                </div>
                <div class="admin-keys-card" data-provider="dtdd" data-label-invalid="Invalid DTDD key">
                    <input name="apiKey"><button class="admin-keys-add-save"></button>
                </div>
            </div>
        </mdui-tab-panel>`);
    render();
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

const fill = (input, value) => {
    input.value = value;
    input.dispatchEvent(new Event("input", { bubbles: true }));
};

beforeEach(async () => {
    confirmAnswer = true;
    posted = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())), snackbar: vi.fn() };
    await load("admin-tabs", "admin-keys");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

describe("admin-keys", () => {
    it("adds a key, clears the field and reloads; 400 says 'invalid key format'", async () => {
        fill(field("steam"), " 0123 ");
        await click('[data-provider="steam"] .admin-keys-add-save');
        expect(posted).toHaveBeenLastCalledWith("/admin/keys/steam/add", { apiKey: "0123" });
        expect(field("steam").value).toBe("");
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Added" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);

        posted.mockResolvedValueOnce(response(400));
        fill(field("dtdd"), "nope");
        await click('[data-provider="dtdd"] .admin-keys-add-save');
        expect(posted).toHaveBeenLastCalledWith("/admin/keys/dtdd/add", { apiKey: "nope" });
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Invalid DTDD key");
        expect(field("dtdd").value).toBe("nope");
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);
    });

    it("posts once on a double click", async () => {
        fill(field("steam"), "0123");
        const button = document.querySelector('[data-provider="steam"] .admin-keys-add-save');
        button.click();
        button.click();
        await flush();

        expect(posted).toHaveBeenCalledTimes(1);
    });

    it("does not post an empty key", async () => {
        await click('[data-provider="steam"] .admin-keys-add-save');

        expect(posted).not.toHaveBeenCalled();
        expect(field("steam").hasAttribute("invalid")).toBe(true);
    });

    it("refreshes and deletes", async () => {
        await click('[data-provider="steam"] .admin-keys-refresh');
        expect(posted).toHaveBeenLastCalledWith("/admin/keys/steam/refresh", {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Refreshed" });

        await click(".admin-keys-delete");
        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete abcd…wxyz?" }));
        expect(posted).toHaveBeenLastCalledWith("/admin/keys/steam/delete", { keyId: "id/1 é" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(2);
    });

    it("does not delete when the confirmation is declined", async () => {
        confirmAnswer = false;
        await click(".admin-keys-delete");

        expect(posted).not.toHaveBeenCalled();
    });

    it("tells a vanished key and an unreachable api apart, and reloads on a vanished one", async () => {
        posted.mockResolvedValueOnce(response(404));
        await click(".admin-keys-delete");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);

        posted.mockResolvedValueOnce(response(502));
        await click('[data-provider="steam"] .admin-keys-refresh');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);
        expect(card("steam").querySelector(".admin-keys-refresh").disabled).toBe(false);
    });
});
