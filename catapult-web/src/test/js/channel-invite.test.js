/*
 * The Invitations tab: "Copy the link" puts the invite link on the clipboard; "Regenerate" asks first
 * (a dialog), posts once however often it is clicked, then refreshes the channel page.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;
let refresh;
let release;
let writeText;

const dialog = () => document.getElementById("invite-regenerate-dialog");
const confirm = () => dialog().querySelector(".invite-regenerate-confirm");

function page() {
    html(`
        <mdui-card id="channel-invite">
            <mdui-text-field id="invite-link" readonly value="https://catapult.test/join?invite=ABC234"></mdui-text-field>
            <mdui-button id="invite-copy-btn"></mdui-button>
            <mdui-button id="invite-regenerate-btn"></mdui-button>
            <mdui-dialog id="invite-regenerate-dialog">
                <mdui-button class="invite-regenerate-dismiss"></mdui-button>
                <mdui-button class="invite-regenerate-confirm"></mdui-button>
            </mdui-dialog>
        </mdui-card>`);
    render();
}

beforeEach(async () => {
    postJson = vi.fn(() => new Promise(resolve => { release = () => resolve(response(204)); }));
    refresh = vi.fn(async () => {});
    writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    await load("channel-invite");
    window.CatapultChannel = { baseUrl: () => "/channel/me", postJson, refresh, showError: vi.fn() };
});

describe("channel-invite", () => {
    it("copies the link", async () => {
        page();

        document.getElementById("invite-copy-btn").click();
        await flush();

        expect(writeText).toHaveBeenCalledWith("https://catapult.test/join?invite=ABC234");
    });

    it("asks before regenerating, then refreshes", async () => {
        page();

        document.getElementById("invite-regenerate-btn").click();
        expect(dialog().open).toBe(true);
        expect(postJson).not.toHaveBeenCalled();

        confirm().click();
        confirm().click();
        await flush();
        expect(postJson).toHaveBeenCalledTimes(1);
        expect(postJson).toHaveBeenCalledWith("/invite/regenerate", {});
        expect(refresh).not.toHaveBeenCalled();

        release();
        await flush();
        expect(refresh).toHaveBeenCalledTimes(1);
    });

    it("dismissing the dialog posts nothing", async () => {
        page();
        document.getElementById("invite-regenerate-btn").click();
        dialog().querySelector(".invite-regenerate-dismiss").click();
        await flush();

        expect(dialog().open).toBe(false);
        expect(postJson).not.toHaveBeenCalled();
    });

    it("keeps the page and frees the button when the request fails", async () => {
        postJson.mockImplementation(async () => response(502));
        page();

        confirm().click();
        await flush();

        expect(refresh).not.toHaveBeenCalled();
        expect(confirm().disabled).toBe(false);
    });
});
