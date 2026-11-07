/*
 * The chat command migration card: the menu shows the tool picked; each line's button copies that line; a read-only
 * token generated on the page takes the placeholder's place in the links, the placeholder coming back once revoked.
 */
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
    await load("api-token-cards", "channel-command-migration");
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

    describe("the read-only token", () => {
        const PLACEHOLDER = "https://catapult.test/api/me/game/summary?token=<READ_TOKEN>";
        const REAL = "https://catapult.test/api/me/game/summary?token=catr_READ";
        const tell = (base, created) =>
            document.dispatchEvent(new CustomEvent("catapult:api-token", { detail: { base, created } }));

        function links() {
            html(`
                <mdui-card id="command-migration" data-summary-url="${PLACEHOLDER}" data-summary-placeholder-url="${PLACEHOLDER}">
                    <mdui-text-field readonly value="${PLACEHOLDER}&f=name">
                        <mdui-button-icon class="command-migration-copy" data-copy="${PLACEHOLDER}&f=name"></mdui-button-icon>
                    </mdui-text-field>
                    <mdui-text-field readonly value="$.name">
                        <mdui-button-icon class="command-migration-copy" data-copy="$.name"></mdui-button-icon>
                    </mdui-text-field>
                </mdui-card>`);
            render();
            return document.querySelectorAll("#command-migration mdui-text-field");
        }

        it("takes the placeholder's place once generated, in each field and its copy button", () => {
            const [link, path] = links();

            tell("/me/api-token/read", { token: "catr_READ", summaryUrl: REAL });

            expect(link.value).toBe(`${REAL}&f=name`);
            expect(link.querySelector("[data-copy]").dataset.copy).toBe(`${REAL}&f=name`);
            expect(path.value).toBeUndefined();
            expect(path.querySelector("[data-copy]").dataset.copy).toBe("$.name");
            expect(document.getElementById("command-migration").dataset.summaryUrl).toBe(REAL);
        });

        it("gives the placeholder back once revoked", () => {
            const [link] = links();
            tell("/me/api-token/read", { token: "catr_READ", summaryUrl: REAL });

            tell("/me/api-token/read", null);

            expect(link.value).toBe(`${PLACEHOLDER}&f=name`);
            expect(document.getElementById("command-migration").dataset.summaryUrl).toBe(PLACEHOLDER);
        });

        it("ignores the full token", () => {
            const [link] = links();

            tell("/me/api-token", { token: "cat_FULL", summaryUrl: "https://catapult.test/elsewhere" });

            expect(link.getAttribute("value")).toBe(`${PLACEHOLDER}&f=name`);
            expect(document.getElementById("command-migration").dataset.summaryUrl).toBe(PLACEHOLDER);
        });
    });
});
