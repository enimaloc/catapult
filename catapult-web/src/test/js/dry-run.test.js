/*
 * dry-run.js: the banner of a member in dry run. "Turn off" opens its confirmation; confirming posts the way out
 * then hides the banner, cancelling only closes it, a failure tells it in the standard snackbar.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;

const PAGE = `
    <div id="dry-run-banner">
        <span>Catapult is in observation mode (dry run): it does not change your channel.</span>
        <button id="dry-run-disable">Turn off</button>
    </div>
    <div id="dry-run-dialog">
        <button id="dry-run-cancel">Cancel</button>
        <button id="dry-run-confirm">Turn off</button>
    </div>`;

const banner = () => document.getElementById("dry-run-banner");
const dialog = () => document.getElementById("dry-run-dialog");

async function click(id) {
    document.getElementById(id).click();
    await flush();
}

beforeEach(async () => {
    document.body.removeAttribute("data-label-action-failed");
    posted = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson: posted };
    html(PAGE);
    await load("channel-page", "dry-run");
});

describe("dry-run banner", () => {
    it("opens the confirmation on Turn off, without sending anything", async () => {
        await click("dry-run-disable");

        expect(dialog().open).toBe(true);
        expect(posted).not.toHaveBeenCalled();
    });

    it("posts the way out once confirmed, then closes the dialog and hides the banner", async () => {
        await click("dry-run-disable");
        await click("dry-run-confirm");

        expect(posted.mock.calls).toEqual([["/me/dry-run/disable", {}]]);
        expect(dialog().open).toBe(false);
        expect(banner().hidden).toBe(true);
    });

    it("only closes the dialog on cancel", async () => {
        await click("dry-run-disable");
        await click("dry-run-cancel");

        expect(dialog().open).toBe(false);
        expect(banner().hidden).toBe(false);
        expect(posted).not.toHaveBeenCalled();
    });

    it("keeps the banner and tells the failure in a snackbar", async () => {
        document.body.dataset.labelActionFailed = "Action failed ({0})";
        posted.mockResolvedValueOnce(response(502));
        await click("dry-run-disable");
        await click("dry-run-confirm");

        expect(banner().hidden).toBe(false);
        expect(dialog().open).toBe(false);
        expect(document.querySelector("mdui-snackbar")?.textContent).toBe("Action failed (502)");
    });

    it("tells an unreachable server, and sends once on a double click", async () => {
        let resolve;
        posted.mockImplementationOnce(() => new Promise((_, reject) => { resolve = reject; }));
        await click("dry-run-disable");
        document.getElementById("dry-run-confirm").click();
        document.getElementById("dry-run-confirm").click();
        resolve(new Error("offline"));
        await flush();

        expect(posted).toHaveBeenCalledTimes(1);
        expect(banner().hidden).toBe(false);
        expect(document.querySelector("mdui-snackbar")).not.toBeNull();
    });

    it("binds once, and does nothing on a page without the banner", async () => {
        render();
        await click("dry-run-disable");
        await click("dry-run-confirm");
        expect(posted).toHaveBeenCalledTimes(1);

        html("<main></main>");
        expect(() => render()).not.toThrow();
    });
});
