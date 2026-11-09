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
        html('<mdui-button href="/auth/login">Log in</mdui-button>');
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
        fetchMock.mockResolvedValueOnce({ ...response(200), url: "http://localhost/channel/enimaloc" });
        html('<a href="/auth/callback?code=1">1</a><span href="/mock/admin">admin</span>');
        tag({ "data-role": "popout" });
        await load("mock-popout");

        expect(click(document.querySelector('[href="/mock/admin"]')).defaultPrevented).toBe(false);
        expect(click(document.querySelector('[href^="/auth"]')).defaultPrevented).toBe(true);
        await flush();

        expect(fetchMock).toHaveBeenCalledWith("/auth/callback?code=1", { credentials: "same-origin" });
        expect(app.location.href).toBe("http://localhost/channel/enimaloc");
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
        expect(app.location.href).toBe("/");
    });

    /** The config page as /mock/admin renders it: both forms, a result line. */
    const configPage = (result = "", appStale = false) => `<main${appStale ? ' data-app-stale="true"' : ""}>
        <p class="mock-result">${result}</p>
        <form id="mock-admin-form" action="/mock/admin">
          <input name="avatarUrl" value="a.png">
          <button id="add" type="submit" formaction="/mock/admin?addBinding=true">add</button>
          <button id="apply" type="submit">apply</button>
        </form>
        <form id="mock-twitchat-form" action="/mock/admin/twitchat">
          <input name="event" value="STREAM_STARTED">
          <button id="send" type="submit">send</button>
        </form>
      </main>`;
    const answer = (body, url = "http://localhost/mock/admin") => ({ ...response(200, body), url });

    /** Clicks a submit button, which submits its form; returns that submit event. */
    async function submitWith(id) {
        let submit = null;
        const form = document.getElementById(id).closest("form");
        form.addEventListener("submit", (event) => { submit = event; });
        click(document.getElementById(id));
        await flush();
        return submit;
    }

    describe("the config's forms", () => {
        beforeEach(async () => {
            vi.spyOn(window, "scrollTo").mockImplementation(() => {});
            app.CatapultChannel = { refresh: vi.fn() };
            html(configPage());
            tag({ "data-role": "popout" });
            await load("mock-popout");
        });

        it("saves the config in the background, swapping in the page it gets back", async () => {
            fetchMock.mockResolvedValue(answer(configPage("saved")));
            const submit = await submitWith("apply");

            expect(submit.defaultPrevented).toBe(true);
            const [url, init] = fetchMock.mock.calls[0];
            expect(url).toBe("/mock/admin");
            expect(init.method).toBe("POST");
            expect(init.body.toString()).toBe("avatarUrl=a.png");
            expect(document.querySelector(".mock-result").textContent).toBe("saved");
            expect(app.CatapultChannel.refresh).not.toHaveBeenCalled(); // its events carry the rest
            expect(app.location.reload).not.toHaveBeenCalled();
            expect(self.href).toBe("");
        });

        it("re-renders the app's page when the config changed what no event carries", async () => {
            fetchMock.mockResolvedValue(answer(configPage("", true)));
            await submitWith("apply");

            expect(app.CatapultChannel.refresh).toHaveBeenCalledOnce();
            expect(app.location.reload).not.toHaveBeenCalled();
        });

        it("posts to the button's formaction, and keeps the swapped-in page working", async () => {
            fetchMock.mockResolvedValue(answer(configPage()));
            await submitWith("add");
            await submitWith("apply");

            expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["/mock/admin?addBinding=true", "/mock/admin"]);
        });

        it("sends a Twitchat event without touching the app, showing the result", async () => {
            fetchMock.mockResolvedValue(answer(configPage("1 payload(s) envoyé(s)")));
            await submitWith("send");

            expect(fetchMock.mock.calls[0][0]).toBe("/mock/admin/twitchat");
            expect(document.querySelector(".mock-result").textContent).toBe("1 payload(s) envoyé(s)");
            expect(app.CatapultChannel.refresh).not.toHaveBeenCalled();
        });

        it("follows to the page it lands on when it isn't the config (logged out)", async () => {
            fetchMock.mockResolvedValue(answer("<main>login</main>", "http://localhost/mock/login"));
            await submitWith("apply");

            expect(self.href).toBe("http://localhost/mock/login");
            expect(app.CatapultChannel.refresh).not.toHaveBeenCalled();
        });
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
