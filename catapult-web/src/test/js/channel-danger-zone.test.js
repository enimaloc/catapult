/*
 * The Settings tab's danger zone: Delete stays disabled until the typed name equals the channel's
 * (case-insensitive, as catapult-api compares it), posts once however often it is clicked, and the
 * card shows its pending state in place after (never the page re-rendered); a pending deletion is
 * cancelled the same way.
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

const hidden = selector => document.querySelector(selector).classList.contains("hidden");
/** Expects the card to show its pending state (`pending`) or its request state, and that alone. */
function shows(pending) {
    expect({
        title: !hidden(".danger-zone-title"), pendingTitle: !hidden(".danger-zone-pending-title"),
        pending: !hidden("#danger-zone-pending"), request: !hidden("#danger-zone-request"),
    }).toEqual({ title: !pending, pendingTitle: pending, pending, request: !pending });
}

/** The card as the server renders it: both states, the one not shown hidden. */
function page(pending) {
    const off = visible => (visible ? "" : ' class="hidden"');
    html(`
        <mdui-card id="channel-danger-zone" data-username="Me" data-delay-days="7"
                   data-label-refused="refused" data-label-unavailable="down">
            <h3><span class="danger-zone-title${pending ? " hidden" : ""}"></span>
                <span class="danger-zone-pending-title${pending ? "" : " hidden"}"></span></h3>
            <div id="danger-zone-pending"${off(pending)}>
                <p id="danger-zone-deletes"${off(pending)}>
                    <span class="danger-zone-deletes-on" data-label="Supprimé le {0}.">${pending ? "Supprimé le 08/10/2026." : ""}</span>
                    <span data-live-time="countdown" data-label-future="dans {0}"
                          ${pending ? 'datetime="2026-10-08T10:00:00Z"' : ""}></span>
                </p>
                <mdui-button id="danger-zone-cancel-btn"></mdui-button>
            </div>
            <div id="danger-zone-request"${off(!pending)}>
                <mdui-button id="danger-zone-delete-btn"></mdui-button>
                <mdui-dialog id="danger-zone-delete-dialog">
                    <mdui-text-field name="confirmUsername"></mdui-text-field>
                    <mdui-button class="danger-zone-dismiss"></mdui-button>
                    <mdui-button class="danger-zone-confirm" disabled></mdui-button>
                </mdui-dialog>
            </div>
        </mdui-card>`);
    render();
}

/** The elements added to or removed from the page from now on (a text written in an empty one aside). */
function counting() {
    const nodes = [];
    new MutationObserver(records => records.forEach(r => nodes.push(...[...r.addedNodes, ...r.removedNodes]
        .filter(node => node.nodeType === 1))))
        .observe(document.body, { childList: true, subtree: true });
    return nodes;
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

    it("posts once on a double click, then shows the pending deletion in place", async () => {
        document.documentElement.lang = "fr";
        vi.spyOn(Date, "now").mockReturnValue(Date.parse("2026-10-27T12:00:00Z"));
        page(false);
        type("me");
        document.getElementById("danger-zone-delete-btn").click();
        type("me");
        const nodes = counting();

        confirm().click();
        confirm().click();
        await flush();
        expect(postJson).toHaveBeenCalledTimes(1);
        expect(postJson).toHaveBeenCalledWith("/channel/me/settings/delete-account", { confirmUsername: "me" });
        expect(hidden("#danger-zone-pending")).toBe(true);

        release();
        await flush();
        expect(refresh).not.toHaveBeenCalled();
        expect(nodes).toHaveLength(0);
        expect(dialog().open).toBe(false);
        shows(true);
        expect(hidden("#danger-zone-deletes")).toBe(false);
        expect(document.querySelector(".danger-zone-deletes-on").textContent).toBe("Supprimé le 03/11/2026.");
        expect(document.querySelector("#danger-zone-pending [data-live-time]").getAttribute("datetime"))
            .toBe("2026-11-03T12:00:00.000Z");
        vi.restoreAllMocks();
    });

    it.each([
        [403, "refused"],
        [401, "refused"],
        [400, "down"],
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
        shows(false);
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
        shows(true);
    });

    it("cancels a pending deletion and shows the card's request state in place", async () => {
        page(true);
        const nodes = counting();

        document.getElementById("danger-zone-cancel-btn").click();
        document.getElementById("danger-zone-cancel-btn").click();
        await flush();
        expect(postJson).toHaveBeenCalledTimes(1);
        expect(postJson).toHaveBeenCalledWith("/channel/me/settings/cancel-deletion", {});

        release();
        await flush();
        expect(refresh).not.toHaveBeenCalled();
        expect(nodes).toHaveLength(0);
        shows(false);
    });

    it("requests the deletion again once cancelled, the dialog emptied", async () => {
        postJson.mockImplementation(async () => response(204));
        page(true);
        document.getElementById("danger-zone-cancel-btn").click();
        await flush();

        document.getElementById("danger-zone-delete-btn").click();
        expect(field().value).toBe("");
        type("Me");
        confirm().click();
        await flush();

        expect(postJson).toHaveBeenLastCalledWith("/channel/me/settings/delete-account", { confirmUsername: "Me" });
        shows(true);
        expect(document.getElementById("danger-zone-cancel-btn").disabled).toBe(false);
    });
});
