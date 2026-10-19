/*
 * help.js: a card's "?" button opens the shared side sheet with the help fetched from
 * /spa/help/{cardId}; Escape closes it (mdui does that); two quick clicks show only the last answer.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let pending;

const panel = () => document.getElementById("help-panel");
const content = () => document.getElementById("help-content");

function page() {
    html(`
        <mdui-navigation-drawer id="help-panel"><div id="help-content"></div></mdui-navigation-drawer>
        <mdui-button-icon data-help="status"></mdui-button-icon>
        <mdui-button-icon data-help="bindings"></mdui-button-icon>`);
    render();
}

const click = id => document.querySelector(`[data-help="${id}"]`).click();

beforeEach(async () => {
    pending = {};
    globalThis.fetch = vi.fn(url => new Promise(resolve => { pending[url] = body => resolve(response(200, body)); }));
    await load("help");
});

describe("help", () => {
    it("opens the side sheet with the fetched content and closes on Escape", async () => {
        page();
        click("status");
        expect(fetch).toHaveBeenCalledWith("/spa/help/status", expect.anything());
        pending["/spa/help/status"]("<h3>Status</h3><p>Hello</p>");
        await flush();

        expect(panel().open).toBe(true);
        expect(content().innerHTML).toContain("<p>Hello</p>");

        // mdui closes a modal drawer on Escape itself and says so with this event; the panel mirrors it.
        panel().open = false;
        panel().dispatchEvent(new Event("close"));
        expect(panel().open).toBe(false);
    });

    it("keeps only the latest answer when two buttons are clicked quickly", async () => {
        page();
        click("status");
        click("bindings");
        pending["/spa/help/bindings"]("<p>second</p>");
        await flush();
        pending["/spa/help/status"]("<p>first</p>");
        await flush();

        expect(content().innerHTML).toContain("second");
        expect(content().innerHTML).not.toContain("first");
        expect(panel().open).toBe(true);
    });

    it("does not open the sheet when the request fails", async () => {
        page();
        fetch.mockRejectedValueOnce(new Error("down"));
        click("status");
        await flush();
        expect(panel().open).toBeFalsy();
    });
});
