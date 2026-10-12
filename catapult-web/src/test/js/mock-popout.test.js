// catapult-web/src/test/js/mock-popout.test.js
/*
 * mock-popout.js: the app's "Mock" button and quick login, and the popout's account switching
 * and config saving, each acting on the other window.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, response } from "./helpers.js";

/** The script tag mock-popout.js reads its role from, as the page renders it. */
function tag(attributes) {
    document.head.querySelectorAll("script").forEach((s) => s.remove());
    const script = document.createElement("script");
    script.setAttribute("src", "/js/mock-popout.js");
    Object.entries(attributes).forEach(([name, value]) => script.setAttribute(name, value));
    document.head.appendChild(script);
}

/** A stand-in window: its location only records where it was sent. */
const fakeWindow = () => ({ location: { href: "", reload: vi.fn() } });

let fetchMock;
let added;

beforeEach(() => {
    fetchMock = vi.fn(async () => response(200));
    vi.stubGlobal("fetch", fetchMock);
    // Every listener the script adds, removed after each test so loads don't pile up.
    added = [];
    const original = document.addEventListener.bind(document);
    vi.spyOn(document, "addEventListener").mockImplementation((...args) => {
        added.push(args);
        original(...args);
    });
});

afterEach(() => {
    added.forEach((args) => document.removeEventListener(...args));
    vi.unstubAllGlobals();
    sessionStorage.clear();
});

const click = (el) => {
    const event = new MouseEvent("click", { bubbles: true, cancelable: true });
    el.dispatchEvent(event);
    return event;
};

describe("in the app", () => {
    it("opens the member's config in the popout from the Mock button", async () => {
        const open = vi.spyOn(window, "open").mockReturnValue(null);
        html('<mdui-button id="mock-popout-btn"></mdui-button>');
        tag({});
        await load("mock-popout");
        click(document.getElementById("mock-popout-btn"));
        expect(open).toHaveBeenCalledWith("/mock/admin", "catapult-mock", "popup,width=780,height=900");
    });

    it("leaves logging in alone: no popout then", async () => {
        const open = vi.spyOn(window, "open").mockReturnValue(null);
        html('<mdui-button href="/oauth2/authorization/twitch">Log in</mdui-button>');
        tag({});
        await load("mock-popout");
        expect(click(document.querySelector("mdui-button")).defaultPrevented).toBe(false);
        expect(open).not.toHaveBeenCalled();
    });
});

describe("in the popout", () => {
    let app;
    let self;

    beforeEach(() => {
        app = fakeWindow();
        self = { href: "" };
        window.opener = app;
        vi.spyOn(window, "location", "get").mockReturnValue(self);
    });

    afterEach(() => { window.opener = null; });

    it("logs the app in with a quick account, staying in the popout on the config", async () => {
        html('<a href="/auth/callback?code=1">1</a><span href="/mock/admin">admin</span>');
        tag({ "data-role": "popout" });
        await load("mock-popout");

        expect(click(document.querySelector('[href="/mock/admin"]')).defaultPrevented).toBe(false);
        expect(click(document.querySelector('[href^="/auth"]')).defaultPrevented).toBe(true);
        await flush();

        expect(fetchMock).toHaveBeenCalledWith("/auth/callback?code=1", { credentials: "same-origin" });
        expect(app.location.href).toBe("/channels");
        expect(self.href).toBe("/mock/admin");
    });

    it("falls back to the plain login in the app when the background one fails", async () => {
        vi.spyOn(console, "warn").mockImplementation(() => {});
        fetchMock.mockRejectedValue(new Error("down"));
        html('<a href="/auth/callback?code=1">1</a>');
        tag({ "data-role": "popout" });
        await load("mock-popout");
        click(document.querySelector("a"));
        await flush();
        expect(app.location.href).toBe("/auth/callback?code=1");
        expect(self.href).toBe("");
    });

    it("logs the app in with the custom account the form built", async () => {
        html('<form id="mock-form"><input id="code-field"></form>');
        tag({ "data-role": "popout" });
        // jwt-select.html's own handler, registered first, fills the code in.
        document.getElementById("mock-form").addEventListener("submit", () => {
            document.getElementById("code-field").value = '{"username":"a b"}';
        });
        await load("mock-popout");
        const submit = new Event("submit", { cancelable: true });
        document.getElementById("mock-form").dispatchEvent(submit);
        await flush();

        expect(submit.defaultPrevented).toBe(true);
        expect(fetchMock).toHaveBeenCalledWith(`/auth/callback?code=${encodeURIComponent('{"username":"a b"}')}`, expect.anything());
        expect(app.location.href).toBe("/channels");
    });

    it("reloads the app once the config is saved, and only then", async () => {
        html('<form id="mock-admin-form"></form>');
        tag({ "data-role": "popout" });
        await load("mock-popout");
        expect(app.location.reload).not.toHaveBeenCalled();

        document.getElementById("mock-admin-form").dispatchEvent(new Event("submit"));
        await load("mock-popout"); // the page the form posted to, redirected back
        expect(app.location.reload).toHaveBeenCalledTimes(1);

        await load("mock-popout");
        expect(app.location.reload).toHaveBeenCalledTimes(1);
    });

    it("does nothing when opened on its own", async () => {
        window.opener = null;
        html('<form id="mock-form"></form>');
        tag({ "data-role": "popout" });
        await load("mock-popout");
        expect(added).toEqual([]); // no click interception
        const submit = new Event("submit", { cancelable: true });
        document.getElementById("mock-form").dispatchEvent(submit);
        expect(submit.defaultPrevented).toBe(false);
    });
});
