/* The chat command migration card: the menu shows the tool picked; each line's button copies that line. */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render } from "./helpers.js";

let writeText;
let showError;

function page() {
    html(`
        <mdui-card id="command-migration" data-label-copied="copied" data-label-copy-failed="nocopy">
            <select id="command-migration-tool"><option value="twitchat">t</option><option value="nightbot">n</option><option value="">none</option></select>
            <div class="command-migration-panel" data-tool="twitchat"></div>
            <div class="command-migration-panel hidden" data-tool="nightbot"></div>
            <mdui-button-icon class="command-migration-copy" data-copy="!commands add !game $(urlfetch https://x/a)"></mdui-button-icon>
            <mdui-button-icon class="command-migration-copy" data-copy="!command options !game -level 500"></mdui-button-icon>
        </mdui-card>`);
    render();
}

beforeEach(async () => {
    showError = vi.fn();
    writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    await load("channel-command-migration");
    window.CatapultChannel = { showError };
});

describe("channel-command-migration", () => {
    it("copies the line of the button clicked", async () => {
        page();
        document.querySelectorAll(".command-migration-copy")[1].click();
        await flush();

        expect(writeText).toHaveBeenCalledWith("!command options !game -level 500");
        expect(showError).toHaveBeenCalledWith("copied");
    });

    it("shows the panel of the tool picked, and keeps it when the menu is cleared", () => {
        page();
        const menu = document.getElementById("command-migration-tool");
        const [twitchat, nightbot] = document.querySelectorAll(".command-migration-panel");

        menu.value = "nightbot";
        menu.dispatchEvent(new Event("change"));
        expect(twitchat.classList.contains("hidden")).toBe(true);
        expect(nightbot.classList.contains("hidden")).toBe(false);

        menu.value = "";
        menu.dispatchEvent(new Event("change"));
        expect(nightbot.classList.contains("hidden")).toBe(false);
    });

    it("says so when the clipboard refuses", async () => {
        writeText.mockRejectedValue(new Error("denied"));
        page();
        document.querySelector(".command-migration-copy").click();
        await flush();

        expect(showError).toHaveBeenCalledWith("nocopy");
    });
});
