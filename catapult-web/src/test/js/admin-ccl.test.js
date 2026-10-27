/*
 * The admin CCL tab: a card starts with its saved descriptors selected, its Save is enabled
 * only while the selection differs from them, and Refresh reloads the tab once catapult-api did.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;

function page() {
    html(`
        <mdui-tab-panel value="ccl" data-admin-lazy>
          <div class="admin-view" data-error="Failed ({0})">
            <mdui-button id="admin-ccl-refresh"></mdui-button>
            <div data-ccl-id="Violent Graphic" data-saved="1,2">
                <div class="admin-ccl-select"></div>
                <button class="admin-ccl-save" disabled></button>
            </div>
            <div data-ccl-id="Gambling" data-saved="">
                <div class="admin-ccl-select"></div>
                <button class="admin-ccl-save" disabled></button>
            </div>
          </div>
        </mdui-tab-panel>`);
    render();
    const panel = document.querySelector("mdui-tab-panel");
    document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "ccl", panel } }));
    return panel;
}

const snackbars = () => Array.from(document.querySelectorAll("mdui-snackbar"), s => s.textContent);
const card = id => document.querySelector(`[data-ccl-id="${id}"]`);

function pick(id, values) {
    const select = card(id).querySelector(".admin-ccl-select");
    select.value = values;
    select.dispatchEvent(new Event("change", { bubbles: true }));
}

beforeEach(async () => {
    posted = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson: posted };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    window.CatapultAdminTabs = { reload: vi.fn(async () => {}) };
    await load("channel-page", "admin-ccl");
});

describe("admin-ccl", () => {
    it("selects each card's saved descriptors", () => {
        page();

        expect(card("Violent Graphic").querySelector(".admin-ccl-select").value).toEqual(["1", "2"]);
        expect(card("Gambling").querySelector(".admin-ccl-select").value).toEqual([]);
    });

    it("enables Save only while the selection differs from the saved one", () => {
        page();
        const save = card("Violent Graphic").querySelector(".admin-ccl-save");

        pick("Violent Graphic", ["1"]);
        expect(save.disabled).toBe(false);

        pick("Violent Graphic", ["2", "1"]);
        expect(save.disabled).toBe(true);
    });

    it("saves the picked descriptors as numbers, then takes them as the saved ones", async () => {
        page();
        pick("Violent Graphic", ["3"]);
        card("Violent Graphic").querySelector(".admin-ccl-save").click();
        await flush();

        expect(posted.mock.calls).toEqual([["/admin/ccl/Violent%20Graphic/mappings", { igdbCategoryIds: [3] }]]);
        expect(card("Violent Graphic").dataset.saved).toBe("3");
        expect(card("Violent Graphic").querySelector(".admin-ccl-save").disabled).toBe(true);
    });

    it("keeps Save enabled when saving fails", async () => {
        posted.mockResolvedValue(response(502));
        page();
        pick("Gambling", ["6"]);
        card("Gambling").querySelector(".admin-ccl-save").click();
        await flush();

        expect(card("Gambling").querySelector(".admin-ccl-save").disabled).toBe(false);
        expect(card("Gambling").dataset.saved).toBe("");
        expect(snackbars()).toEqual(["Failed (502)"]);
    });

    it("says so when the save can't be sent at all", async () => {
        posted.mockRejectedValue(new TypeError("offline"));
        page();
        pick("Gambling", ["6"]);
        card("Gambling").querySelector(".admin-ccl-save").click();
        await flush();

        expect(card("Gambling").querySelector(".admin-ccl-save").disabled).toBe(false);
        expect(snackbars()).toEqual(["Failed (—)"]);
    });

    it("says so when the refresh fails, and lets it be tried again", async () => {
        posted.mockResolvedValue(response(503));
        page();
        const refresh = document.getElementById("admin-ccl-refresh");
        refresh.click();
        await flush();

        expect(snackbars()).toEqual(["Failed (503)"]);
        expect(refresh.disabled).toBe(false);
        expect(refresh.loading).toBe(false);
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
    });

    it("refreshes from Twitch, then reloads the tab", async () => {
        const panel = page();
        document.getElementById("admin-ccl-refresh").click();
        await flush();

        expect(posted.mock.calls).toEqual([["/admin/ccl/refresh", {}]]);
        expect(CatapultAdminTabs.reload).toHaveBeenCalledWith(panel);
    });
});
