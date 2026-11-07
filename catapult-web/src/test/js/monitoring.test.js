// catapult-web/src/test/js/monitoring.test.js
/*
 * monitoring.js: Sentry set up from the page's config, and a breadcrumb for each live update
 * stream event.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load } from "./helpers.js";

/** An EventSource stand-in: an EventTarget that remembers its URL. */
class FakeEventSource extends EventTarget {
    constructor(url) {
        super();
        this.url = url;
    }
}

const CONFIG = { dsn: "https://key@sentry.test/1", environment: "test", release: "catapult-web@abc",
    tracesSampleRate: 0.5, user: "8fb1c22b-b3e8-d6e5-a99b-23b552ba9aa2" };

let Sentry;
const nativeWarn = console.warn;

afterEach(() => {
    console.warn = nativeWarn;
});

beforeEach(() => {
    Sentry = {
        init: vi.fn(),
        setUser: vi.fn(),
        addBreadcrumb: vi.fn(),
        browserTracingIntegration: vi.fn(() => "tracing"),
        replayIntegration: vi.fn(() => "replay"),
        httpClientIntegration: vi.fn(() => "httpClient"),
    };
    vi.stubGlobal("Sentry", Sentry);
    vi.stubGlobal("EventSource", FakeEventSource);
    window.Catapult = { sentry: CONFIG };
});

describe("with a DSN", () => {
    it("reports through catapult-web, replaying no page on an error alone, inputs masked", async () => {
        await load("monitoring");

        expect(Sentry.init).toHaveBeenCalledWith(expect.objectContaining({
            dsn: CONFIG.dsn, environment: "test", release: "catapult-web@abc", tracesSampleRate: 0.5,
            tunnel: "/monitoring/tunnel", sendDefaultPii: false,
            replaysSessionSampleRate: 0, replaysOnErrorSampleRate: 0,
            integrations: ["tracing", "replay", "httpClient"],
        }));
        expect(Sentry.replayIntegration).toHaveBeenCalledWith(expect.objectContaining({
            maskAllInputs: true, block: [".darkreader", ".sentry-block"],
        }));
        expect(Sentry.httpClientIntegration).toHaveBeenCalledWith({
            failedRequestStatusCodes: [[500, 599]], failedRequestTargets: [window.location.origin],
        });
        expect(Sentry.setUser).toHaveBeenCalledWith({ id: CONFIG.user });
    });

    it("replays whole sessions when the config asks for them", async () => {
        window.Catapult = { sentry: { ...CONFIG, replaysSessionSampleRate: 1.0 } };
        await load("monitoring");

        expect(Sentry.init.mock.calls[0][0].replaysSessionSampleRate).toBe(1.0);
    });

    it("names no one when logged out", async () => {
        window.Catapult = { sentry: { ...CONFIG, user: null, release: null } };
        await load("monitoring");

        expect(Sentry.setUser).not.toHaveBeenCalled();
        expect(Sentry.init.mock.calls[0][0].release).toBeUndefined();
    });

    it("leaves a breadcrumb for each stream event before its listener runs, without its data", async () => {
        await load("monitoring");
        const source = new window.EventSource("/events/nav");
        const seen = [];
        source.addEventListener("NavChannelLive", (event) => seen.push([event.data, Sentry.addBreadcrumb.mock.calls.length]));

        source.dispatchEvent(new MessageEvent("NavChannelLive", { data: '{"username":"a"}' }));

        expect(seen).toEqual([['{"username":"a"}', 1]]);
        expect(Sentry.addBreadcrumb).toHaveBeenCalledWith({
            category: "sse", message: "NavChannelLive", level: "info", data: { url: "/events/nav" },
        });
    });

    it("leaves a breadcrumb when a stream opens or drops", async () => {
        await load("monitoring");
        const source = new window.EventSource("/events/channel/a");
        const opened = vi.fn();
        source.addEventListener("open", opened);

        source.dispatchEvent(new Event("open"));
        source.dispatchEvent(new Event("error"));

        expect(opened).toHaveBeenCalledOnce();
        expect(Sentry.addBreadcrumb.mock.calls.map(([crumb]) => [crumb.message, crumb.level]))
            .toEqual([["open", "info"], ["error", "warning"]]);
    });

    it("passes listener objects through untouched", async () => {
        await load("monitoring");
        const source = new window.EventSource("/events/nav");
        const listener = { handleEvent: vi.fn() };
        source.addEventListener("NavChannelsChanged", listener);

        source.dispatchEvent(new Event("NavChannelsChanged"));

        expect(listener.handleEvent).toHaveBeenCalledOnce();
        expect(Sentry.addBreadcrumb).not.toHaveBeenCalled();
    });

    it("does without streams where the browser has none", async () => {
        vi.stubGlobal("EventSource", undefined);
        await load("monitoring");

        expect(Sentry.init).toHaveBeenCalled();
        expect(window.EventSource).toBeUndefined();
    });

    it("silences the replay's warnings about style sheets it may not read, and only those", async () => {
        const warned = vi.fn();
        console.warn = warned;
        await load("monitoring");

        console.warn("Cannot get CSS styles from text's parentNode. Error: SecurityError", {});
        console.warn("something else", 1);

        expect(warned.mock.calls).toEqual([["something else", 1]]);
    });
});

describe("the read-only token in a URL", () => {
    const TOKEN = "catr_" + "s".repeat(43);
    /** The hook monitoring.js gives Sentry.init under {@code name}. */
    const hook = async (name) => {
        await load("monitoring");
        return Sentry.init.mock.calls[0][0][name];
    };

    it("is masked in a breadcrumb's URL, and only the token parameter", async () => {
        const beforeBreadcrumb = await hook("beforeBreadcrumb");

        const crumb = beforeBreadcrumb({ category: "fetch",
            data: { url: `/api/me/game?lang=fr&token=${TOKEN}&widget_token=w`, method: "GET" } });

        expect(crumb.data).toEqual({ url: "/api/me/game?lang=fr&token=***&widget_token=w", method: "GET" });
    });

    it("is masked in a navigation breadcrumb's from and to", async () => {
        const beforeBreadcrumb = await hook("beforeBreadcrumb");

        const crumb = beforeBreadcrumb({ category: "navigation",
            data: { from: `/docs?token=${TOKEN}`, to: `/docs?token=${TOKEN}#x` } });

        expect(crumb.data).toEqual({ from: "/docs?token=***", to: "/docs?token=***#x" });
    });

    it("leaves a breadcrumb without data as it is", async () => {
        const beforeBreadcrumb = await hook("beforeBreadcrumb");

        expect(beforeBreadcrumb({ category: "ui.click", message: "button" }))
            .toEqual({ category: "ui.click", message: "button" });
    });

    it("is masked in a transaction's spans and request", async () => {
        const beforeSendTransaction = await hook("beforeSendTransaction");

        const transaction = beforeSendTransaction({
            request: { url: `https://catapult.test/api/me/game?token=${TOKEN}` },
            spans: [
                { op: "http.client", description: `GET /api/me/game?token=${TOKEN}`,
                    data: { url: `/api/me/game?token=${TOKEN}`, "http.query": `?token=${TOKEN}`,
                        "http.method": "GET" } },
                { op: "ui.long-task", description: "Main UI thread blocked" },
            ],
        });

        expect(transaction.request.url).toBe("https://catapult.test/api/me/game?token=***");
        expect(transaction.spans[0]).toEqual({ op: "http.client", description: "GET /api/me/game?token=***",
            data: { url: "/api/me/game?token=***", "http.query": "?token=***", "http.method": "GET" } });
        expect(transaction.spans[1].description).toBe("Main UI thread blocked");
    });

    it("is masked in an error's request, URL and query string", async () => {
        const beforeSend = await hook("beforeSend");

        const event = beforeSend({ request: { url: `https://catapult.test/api-docs?token=${TOKEN}`,
            query_string: `token=${TOKEN}&a=1` } });

        expect(event.request).toEqual({ url: "https://catapult.test/api-docs?token=***",
            query_string: "token=***&a=1" });
    });

    it("is masked in an error's query string given as pairs", async () => {
        const beforeSend = await hook("beforeSend");

        const event = beforeSend({ request: { query_string: [["token", TOKEN], ["a", "1"]] } });

        expect(event.request.query_string).toEqual([["token", "***"], ["a", "1"]]);
    });

    it("lets an event without a request through", async () => {
        const beforeSend = await hook("beforeSend");
        const beforeSendTransaction = await hook("beforeSendTransaction");

        expect(beforeSend({ message: "x" })).toEqual({ message: "x" });
        expect(beforeSendTransaction({ transaction: "/" })).toEqual({ transaction: "/" });
    });
});

describe("without one", () => {
    it("does nothing when the page has no Sentry config", async () => {
        window.Catapult = { sentry: null };
        await load("monitoring");

        expect(Sentry.init).not.toHaveBeenCalled();
        expect(window.EventSource).toBe(FakeEventSource);
    });

    it("does nothing when the SDK did not load (blocked)", async () => {
        vi.stubGlobal("Sentry", undefined);
        await load("monitoring");

        expect(window.EventSource).toBe(FakeEventSource);
    });
});
