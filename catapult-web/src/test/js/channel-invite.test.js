/*
 * The Invitations tab: "Copy the link" puts the invite link on the clipboard; "Regenerate" asks first
 * (a dialog), posts once however often it is clicked, then shows the new code and link in place (the
 * channel page refreshed only when the answer carries none, as an older catapult-api's).
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;
let refresh;
let release;
let writeText;
let showError;

const dialog = () => document.getElementById("invite-regenerate-dialog");
const confirm = () => dialog().querySelector(".invite-regenerate-confirm");

function page() {
    html(`
        <mdui-card id="channel-invite" data-label-refused="refused" data-label-rate-limited="slow" data-label-unavailable="down" data-label-copied="copied" data-label-copy-failed="nocopy">
            <strong class="invite-code">ABC234</strong>
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
    showError = vi.fn();
    postJson = vi.fn(() => new Promise(resolve => { release = (answer = response(204)) => resolve(answer); }));
    refresh = vi.fn(async () => {});
    writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    await load("channel-invite");
    window.CatapultChannel = { baseUrl: () => "/channel/me", refresh, showError };
    window.CatapultCsrf = { postJson };
});

describe("channel-invite", () => {
    it("copies the link", async () => {
        page();

        document.getElementById("invite-copy-btn").click();
        await flush();

        expect(writeText).toHaveBeenCalledWith("https://catapult.test/join?invite=ABC234");
        expect(showError).toHaveBeenCalledWith("copied");
    });

    it("says so when the clipboard refuses", async () => {
        writeText.mockRejectedValue(new Error("denied"));
        page();

        document.getElementById("invite-copy-btn").click();
        await flush();

        expect(showError).toHaveBeenCalledWith("nocopy");
    });

    it("asks before regenerating, then shows the new code and link in place", async () => {
        page();
        const nodes = [];
        new MutationObserver(records => records.forEach(r => nodes.push(...[...r.addedNodes, ...r.removedNodes]
            .filter(node => node.nodeType === 1)))).observe(document.body, { childList: true, subtree: true });

        document.getElementById("invite-regenerate-btn").click();
        confirm().click();
        confirm().click();
        await flush();
        expect(postJson).toHaveBeenCalledTimes(1);
        expect(confirm().disabled).toBe(true);

        release(response(200, { code: "XYZ789", inviteUrl: "https://catapult.test/join?invite=XYZ789" }));
        await flush();
        expect(refresh).not.toHaveBeenCalled();
        expect(nodes).toHaveLength(0);
        expect(dialog().open).toBe(false);
        expect(confirm().disabled).toBe(false);
        expect(document.querySelector(".invite-code").textContent).toBe("XYZ789");

        document.getElementById("invite-copy-btn").click();
        await flush();
        expect(writeText).toHaveBeenCalledWith("https://catapult.test/join?invite=XYZ789");
    });

    it("refreshes when the answer carries no code, as an older catapult-api's", async () => {
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

    it.each([[403, "refused"], [429, "slow"], [502, "down"]])("tells the member about a %i, closes the dialog and frees the button", async (status, label) => {
        postJson.mockImplementation(async () => response(status));
        page();
        document.getElementById("invite-regenerate-btn").click();

        confirm().click();
        await flush();

        expect(showError).toHaveBeenCalledWith(label);
        expect(dialog().open).toBe(false);
        expect(refresh).not.toHaveBeenCalled();
        expect(confirm().disabled).toBe(false);
    });
});
