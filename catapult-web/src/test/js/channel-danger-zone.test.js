/*
 * The Settings tab's danger zone: Delete stays disabled until the typed name equals the channel's
 * (case-insensitive, as catapult-api compares it), posts once however often it is clicked, and the
 * channel page is refreshed after; a pending deletion is cancelled the same way.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;
let refresh;
let release;
let showError;

const dialog = () => document.getElementById("danger-zone-delete-dialog");
const field = () => dialog().querySelector('[name="confirmUsername"]');
const confirm = () => dialog().querySelector(".danger-zone-confirm");

function type(value) {
    field().value = value;
    field().dispatchEvent(new Event("input", { bubbles: true }));
}

function page(pending) {
    html(`
        <mdui-card id="channel-danger-zone" data-username="Me" data-label-refused="refused" data-label-unavailable="down">
            ${pending
                ? `<mdui-button id="danger-zone-cancel-btn"></mdui-button>`
                : `<mdui-button id="danger-zone-delete-btn"></mdui-button>
                   <mdui-dialog id="danger-zone-delete-dialog">
                       <mdui-text-field name="confirmUsername"></mdui-text-field>
                       <mdui-button class="danger-zone-dismiss"></mdui-button>
                       <mdui-button class="danger-zone-confirm" disabled></mdui-button>
                   </mdui-dialog>`}
        </mdui-card>`);
    render();
}

beforeEach(async () => {
    postJson = vi.fn(() => new Promise(resolve => { release = () => resolve(response(204)); }));
    refresh = vi.fn(async () => {});
    await load("channel-danger-zone");
    showError = vi.fn();
    window.CatapultChannel = { baseUrl: () => "/channel/me", refresh, showError };
    window.CatapultCsrf = { postJson };
});

describe("channel-danger-zone", () => {
    it("enables Delete only when the typed name matches, ignoring case", () => {
        page(false);
        expect(confirm().disabled).toBe(true);

        type("m");
        expect(confirm().disabled).toBe(true);
        type("ME");
        expect(confirm().disabled).toBe(false);
        type("Mee");
        expect(confirm().disabled).toBe(true);
    });

    it("opens the dialog emptied and closes it on dismiss", () => {
        page(false);
        type("Me");

        document.getElementById("danger-zone-delete-btn").click();
        expect(dialog().open).toBe(true);
        expect(field().value).toBe("");
        expect(confirm().disabled).toBe(true);

        dialog().querySelector(".danger-zone-dismiss").click();
        expect(dialog().open).toBe(false);
    });

    it("never posts while the name does not match", async () => {
        page(false);
        type("nope");
        confirm().click();
        await flush();

        expect(postJson).not.toHaveBeenCalled();
    });

    it("posts once on a double click, then refreshes the channel page", async () => {
        page(false);
        type("me");

        confirm().click();
        confirm().click();
        await flush();
        expect(postJson).toHaveBeenCalledTimes(1);
        expect(postJson).toHaveBeenCalledWith("/channel/me/settings/delete-account", { confirmUsername: "me" });
        expect(refresh).not.toHaveBeenCalled();

        release();
        await flush();
        expect(refresh).toHaveBeenCalledTimes(1);
    });

    it.each([
        [403, "refused"],
        [401, "refused"],
        [502, "down"],
    ])("keeps the page, frees the button and shows a localized message on %i", async (status, label) => {
        postJson.mockImplementation(async () => response(status));
        page(false);
        type("Me");

        confirm().click();
        await flush();

        expect(refresh).not.toHaveBeenCalled();
        expect(confirm().disabled).toBe(false);
        expect(showError).toHaveBeenCalledWith(label);
    });

    it("keeps the page, frees the button and says unavailable when the network fails", async () => {
        postJson.mockImplementation(async () => { throw new TypeError("Failed to fetch"); });
        page(true);
        const cancel = document.getElementById("danger-zone-cancel-btn");

        cancel.click();
        await flush();

        expect(refresh).not.toHaveBeenCalled();
        expect(cancel.disabled).toBe(false);
        expect(showError).toHaveBeenCalledWith("down");
    });

    it("cancels a pending deletion and refreshes", async () => {
        page(true);

        document.getElementById("danger-zone-cancel-btn").click();
        document.getElementById("danger-zone-cancel-btn").click();
        await flush();
        expect(postJson).toHaveBeenCalledTimes(1);
        expect(postJson).toHaveBeenCalledWith("/channel/me/settings/cancel-deletion", {});

        release();
        await flush();
        expect(refresh).toHaveBeenCalledTimes(1);
    });
});
