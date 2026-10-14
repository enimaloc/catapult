import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, response } from "./helpers.js";

const ROUTES = [
    { id: "", templateUrl: "/spa/landing", dynamic: false },
    { id: "privacy", templateUrl: "/spa/privacy", dynamic: false },
    { id: "channel", templateUrl: "/spa/channel", dynamic: true },
];

let fetchMock;

beforeEach(async () => {
    html(`
        <a id="home" data-link href="/">Home</a>
        <a id="privacy" data-link href="/privacy">Privacy</a>
        <a id="external" data-link href="https://example.org/x">External</a>
        <a id="plain" href="/privacy">Plain</a>
        <progress id="nav-progress" hidden></progress>
        <main id="app"></main>`);
    window.Catapult = { page: "privacy", spa: ROUTES, titles: { privacy: "Privacy | Catapult" } };
    fetchMock = vi.fn(async () => response(200, "<p>page</p>"));
    vi.stubGlobal("fetch", fetchMock);
    window.scrollTo = vi.fn();
    await load("spa");
});

describe("resolveRoute", () => {
    it("matches a static route by its id", () => {
        expect(CatapultSpa.resolveRoute("privacy")).toEqual({ route: ROUTES[1], param: null });
        expect(CatapultSpa.resolveRoute("")).toEqual({ route: ROUTES[0], param: null });
    });

    it("matches <id>/<param> for a dynamic route only", () => {
        expect(CatapultSpa.resolveRoute("channel/enimaloc")).toEqual({ route: ROUTES[2], param: "enimaloc" });
        expect(CatapultSpa.resolveRoute("privacy/x")).toEqual({ route: undefined, param: null });
    });

    it("finds nothing for unknown, deeper or param-less paths", () => {
        expect(CatapultSpa.resolveRoute("nope").route).toBeUndefined();
        expect(CatapultSpa.resolveRoute("channel/a/b").route).toBeUndefined();
        expect(CatapultSpa.resolveRoute("channel/").route).toBeUndefined();
    });
});

describe("navigate", () => {
    it("renders the fragment, pushes history, updates the title and the active link", async () => {
        const rendered = vi.fn();
        document.addEventListener("catapult:render", rendered);

        await CatapultSpa.navigate("privacy");

        expect(fetchMock).toHaveBeenCalledWith("/spa/privacy");
        expect(document.getElementById("app").innerHTML).toBe("<p>page</p>");
        expect(rendered).toHaveBeenCalledOnce();
        expect(location.pathname).toBe("/privacy");
        expect(history.state).toEqual({ spa: true, path: "privacy" });
        expect(document.title).toBe("Privacy | Catapult");
        expect(document.getElementById("privacy").classList.contains("active")).toBe(true);
        expect(document.getElementById("home").classList.contains("active")).toBe(false);
        expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "instant" });
        expect(document.getElementById("nav-progress").hidden).toBe(true);
    });

    it("names the dynamic param in the page's title", async () => {
        window.Catapult.titles.channel = "{0} - Catapult";

        await CatapultSpa.navigate("channel/enimaloc");

        expect(document.title).toBe("enimaloc - Catapult");
    });

    it("renders with the new URL already in place, for scripts reading it", async () => {
        let seen = null;
        document.addEventListener("catapult:render", () => { seen = location.pathname; }, { once: true });

        await CatapultSpa.navigate("channel/other");

        expect(seen).toBe("/channel/other");
    });

    it("appends the dynamic param and the query string", async () => {
        await CatapultSpa.navigate("channel/enimaloc", true, "?page=2");

        expect(fetchMock).toHaveBeenCalledWith("/spa/channel/enimaloc?page=2");
        expect(location.pathname + location.search).toBe("/channel/enimaloc?page=2");
    });

    it("pushes / for the landing page", async () => {
        history.replaceState(null, "", "/privacy");
        await CatapultSpa.navigate("");

        expect(location.pathname).toBe("/");
    });

    it("does not push history when asked not to", async () => {
        history.replaceState({ keep: true }, "", "/privacy");
        await CatapultSpa.navigate("channel/x", false);

        expect(history.state).toEqual({ keep: true });
        expect(location.pathname).toBe("/privacy");
    });

    it("falls back to /spa/<path> for unknown routes and renders the error fragment as-is", async () => {
        fetchMock.mockResolvedValueOnce(response(404, "<p>not found</p>"));
        document.title = "Before";

        await CatapultSpa.navigate("nope");

        expect(fetchMock).toHaveBeenCalledWith("/spa/nope");
        expect(document.getElementById("app").innerHTML).toBe("<p>not found</p>");
        expect(document.title).toBe("Before");
        expect(window.scrollTo).not.toHaveBeenCalled();
    });

    it("keeps the current title when the route has none", async () => {
        document.title = "Before";
        await CatapultSpa.navigate("channel/x");

        expect(document.title).toBe("Before");
    });

    it("leaves the page alone and hides the progress bar when the request fails", async () => {
        fetchMock.mockRejectedValueOnce(new TypeError("offline"));
        const rendered = vi.fn();
        document.addEventListener("catapult:render", rendered);

        await CatapultSpa.navigate("privacy");

        expect(document.getElementById("app").innerHTML).toBe("");
        expect(rendered).not.toHaveBeenCalled();
        expect(document.getElementById("nav-progress").hidden).toBe(true);
    });
});

describe("the navbar's channels", () => {
    const NAV = '<a id="c1" data-link class="nav-channel" href="/channel/enimaloc">e</a>'
        + '<a id="c2" data-link class="nav-channel" href="/channel/other">o</a>';

    async function loadWithNav(path) {
        document.body.insertAdjacentHTML("afterbegin", '<nav id="nav-channels"></nav>');
        history.replaceState(null, "", path);
        await load("spa");
        await flush();
    }

    it("fills the navbar once the page is shown, marking the open channel", async () => {
        fetchMock.mockImplementation(async () => response(200, NAV));

        await loadWithNav("/channel/other");

        expect(fetchMock).toHaveBeenCalledWith("/spa/nav-channels");
        expect(document.querySelectorAll("#nav-channels a")).toHaveLength(2);
        expect(document.getElementById("c2").classList.contains("active")).toBe(true);
        expect(document.getElementById("c1").classList.contains("active")).toBe(false);
    });

    it("leaves the navbar empty when they can't be fetched", async () => {
        fetchMock.mockImplementation(async () => response(500, NAV));
        await loadWithNav("/");
        expect(document.getElementById("nav-channels").innerHTML).toBe("");

        fetchMock.mockImplementation(async () => { throw new TypeError("offline"); });
        await CatapultSpa.loadNavChannels();
        expect(document.getElementById("nav-channels").innerHTML).toBe("");
    });

    it("can be reloaded, keeping the open channel marked", async () => {
        fetchMock.mockImplementation(async () => response(200, ""));
        await loadWithNav("/channel/enimaloc");

        fetchMock.mockImplementation(async () => response(200, NAV));
        await CatapultSpa.loadNavChannels();

        expect(document.getElementById("c1").classList.contains("active")).toBe(true);
    });

    it("isn't fetched without a navbar to fill (logged out)", async () => {
        expect(fetchMock).not.toHaveBeenCalledWith("/spa/nav-channels");
    });

});

describe("link and history handling", () => {
    it("marks the initial page's link as active on load, from its URL", async () => {
        history.replaceState(null, "", "/privacy");
        await load("spa");

        expect(document.getElementById("privacy").classList.contains("active")).toBe(true);
    });

    it("marks the open channel's navbar link as active on load", async () => {
        document.body.insertAdjacentHTML("beforeend", '<a id="nav-chan" data-link href="/channel/enimaloc">e</a>');
        window.Catapult.page = "channel";
        history.replaceState(null, "", "/channel/enimaloc");
        await load("spa");

        expect(document.getElementById("nav-chan").classList.contains("active")).toBe(true);
    });

    it("intercepts clicks on same-origin data-link anchors", async () => {
        const click = new MouseEvent("click", { bubbles: true, cancelable: true });
        document.getElementById("privacy").dispatchEvent(click);
        await flush();

        expect(click.defaultPrevented).toBe(true);
        expect(fetchMock).toHaveBeenCalledWith("/spa/privacy");
    });

    it("lets clicks on external or plain links through", async () => {
        // Runs after spa.js's own listener: records its decision, then stops jsdom from
        // attempting a real navigation (which it doesn't implement).
        const decisions = [];
        window.addEventListener("click", event => {
            decisions.push(event.defaultPrevented);
            event.preventDefault();
        });
        for (const id of ["external", "plain"]) {
            document.getElementById(id).dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
        }

        expect(decisions).toEqual([false, false]);
        await flush();

        expect(fetchMock).not.toHaveBeenCalled();
    });

    it("re-renders the current location on popstate without pushing", async () => {
        history.replaceState(null, "", "/channel/enimaloc?status=AUTO");
        window.dispatchEvent(new PopStateEvent("popstate"));
        await flush();

        expect(fetchMock).toHaveBeenCalledWith("/spa/channel/enimaloc?status=AUTO");
        expect(history.state).toBeNull();
    });

    it("dispatches catapult:render once the document has loaded", () => {
        const rendered = vi.fn();
        document.addEventListener("catapult:render", rendered);

        document.dispatchEvent(new Event("DOMContentLoaded"));

        expect(rendered).toHaveBeenCalledOnce();
    });
});
