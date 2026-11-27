/*
 * The title card's Guest Star box: its button leaves for catapult-api's Guest Star login, and the way back
 * (?guest_star=ok, ?error=guest_star_*) is told once, the code then leaving the address.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render } from "./helpers.js";

let showMessage;
let showError;

function page(withButton = true) {
    html(`
        <div id="guest-stars-consent" data-label-done="done" data-label-failed="failed"
             data-label-mismatch="mismatch" data-label-cancelled="cancelled">
            ${withButton ? '<mdui-button id="guest-stars-authorize-btn"></mdui-button>' : ""}
        </div>`);
    render();
}

function at(search) {
    history.replaceState(null, "", `/channel/me${search}#settings`);
}

beforeEach(async () => {
    await load("channel-guest-stars");
    showMessage = vi.fn();
    showError = vi.fn();
    window.CatapultChannel = { showMessage, showError };
});

afterEach(() => {
    vi.unstubAllGlobals();
    at("");
});

describe("channel-guest-stars", () => {
    it("leaves for the Guest Star login", () => {
        const assign = vi.fn();
        vi.stubGlobal("location", { href: "http://localhost/channel/me#settings", assign });
        page();

        document.getElementById("guest-stars-authorize-btn").click();

        expect(assign).toHaveBeenCalledWith("/oauth2/authorization/twitch-guest-star");
    });

    it("tells a given authorization once, the code leaving the address", () => {
        at("?guest_star=ok");
        page(false);

        expect(showMessage).toHaveBeenCalledWith("done");
        expect(location.search).toBe("");
        expect(location.hash).toBe("#settings");
    });

    it("tells a refused authorization as an error, a cancelled one as a message", () => {
        at("?error=guest_star_mismatch");
        page();
        expect(showError).toHaveBeenCalledWith("mismatch");

        at("?error=guest_star_cancelled");
        page();
        expect(showMessage).toHaveBeenCalledWith("cancelled");
        expect(location.search).toBe("");
    });

    it("leaves another card's error alone", () => {
        at("?error=osu_failed");
        page();

        expect(showError).not.toHaveBeenCalled();
        expect(location.search).toBe("?error=osu_failed");
    });
});
