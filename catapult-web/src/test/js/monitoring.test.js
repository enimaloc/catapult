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
    it("reports through catapult-web, replaying only pages with an error, inputs masked", async () => {
        await load("monitoring");

        expect(Sentry.init).toHaveBeenCalledWith(expect.objectContaining({
            dsn: CONFIG.dsn, environment: "test", release: "catapult-web@abc", tracesSampleRate: 0.5,
            tunnel: "/monitoring/tunnel", sendDefaultPii: false,
            replaysSessionSampleRate: 0, replaysOnErrorSampleRate: 1.0,
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
