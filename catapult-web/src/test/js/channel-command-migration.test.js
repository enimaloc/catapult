/* The chat command migration card: each line's button copies that line, and says whether it could. */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render } from "./helpers.js";

let writeText;
let showError;

function page() {
    html(`
        <mdui-card id="command-migration" data-label-copied="copied" data-label-copy-failed="nocopy">
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

    it("says so when the clipboard refuses", async () => {
        writeText.mockRejectedValue(new Error("denied"));
        page();
        document.querySelector(".command-migration-copy").click();
        await flush();

        expect(showError).toHaveBeenCalledWith("nocopy");
    });
});
