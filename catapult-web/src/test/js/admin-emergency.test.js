/*
 * The admin emergency tab: a provider stopped from the form or resumed from its row, each once confirmed; the tab
 * patched after.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render } from "./helpers.js";

let tabs;
let confirmAnswer;
let sent;

function view() {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="emergency">
            <div class="admin-view admin-emergency" data-lift-confirm="Resume?" data-lift-description="Lifts"
                 data-stop-confirm="Stop?" data-stop-description="Until resumed" data-lifted="Resumed." data-stopped="Stopped.">
                <button class="admin-em-lift" id="lift" data-provider="XBOX"></button>
                <div class="admin-em-stop">
                    <mdui-select name="provider"></mdui-select>
                    <button class="admin-em-stop-button" id="stop"></button>
                </div>
            </div>
        </mdui-tab-panel>`);
    document.getElementById("admin-tabs").value = "emergency";
    render();
    document.querySelector('[name="provider"]').value = "STEAM";
}

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
    await load("channel-page", "admin-emergency");
    window.CatapultAdminTabs = tabs;
    window.mdui = { ...(window.mdui ?? {}), snackbar: vi.fn() };
});

describe("admin-emergency", () => {
    it("resumes a provider from its row once confirmed, then patches the tab", async () => {
        view();
        await click("#lift");

        expect(tabs.confirmed.mock.calls[0].slice(1)).toEqual(["Resume?", "Lifts"]);
        expect(tabs.post.mock.calls[0][2]).toBe("/admin/emergency/resume?provider=XBOX");
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Resumed." });
        expect(tabs.patch).toHaveBeenCalledWith(document.querySelector('mdui-tab-panel[value="emergency"]'));
    });

    it("stops the provider picked in the form", async () => {
        view();
        await click("#stop");

        expect(tabs.post.mock.calls[0][2]).toBe("/admin/emergency/stop?provider=STEAM");
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Stopped." });
    });

    it("sends nothing when not confirmed, and patches nothing when refused", async () => {
        view();
        confirmAnswer = false;
        await click("#lift");
        expect(tabs.post).not.toHaveBeenCalled();

        confirmAnswer = true;
        sent = 400;
        await click("#stop");
        expect(tabs.patch).not.toHaveBeenCalled();
    });
});
