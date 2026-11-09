/*
 * twitchat-stream.js: the owner's dispatch stream is open exactly while OBS is, and each
 * TwitchatDispatch it carries becomes a catapult:twitchat:dispatch for twitchat.js.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load } from "./helpers.js";

let sources;

class FakeEventSource {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;
    constructor(url) {
        this.readyState = FakeEventSource.CONNECTING;
        this.url = url;
        this.listeners = {};
        this.closed = false;
        sources.push(this);
    }
    addEventListener(type, handler) { this.listeners[type] = handler; }
    close() { this.closed = true; }
}

beforeEach(async () => {
    sources = [];
    vi.stubGlobal("EventSource", FakeEventSource);
    await load("twitchat-stream");
});

afterEach(() => vi.unstubAllGlobals());

const obs = (state) => document.dispatchEvent(new CustomEvent(`catapult:obs:${state}`));

describe("twitchat-stream", () => {
    it("opens the owner's stream once OBS connects, only once", () => {
        obs("connected");
        obs("connected");
        expect(sources.map((s) => s.url)).toEqual(["/me/twitchat/events"]);
    });

    it("opens the page's events URL, /me/twitchat/events by default", () => {
        obs("connected");
        expect(sources.map((s) => s.url)).toEqual(["/me/twitchat/events"]);
        obs("disconnected");

        sources = [];
        document.body.dataset.twitchatEventsUrl = "/widget/obs/t-1/events";
        obs("connected");
        expect(sources.map((s) => s.url)).toEqual(["/widget/obs/t-1/events"]);
    });

    it("lets the browser reconnect a stream the server closed (the widget's timeout)", () => {
        vi.useFakeTimers();
        obs("connected");
        sources[0].readyState = FakeEventSource.CONNECTING;
        sources[0].listeners.error({});
        vi.advanceTimersByTime(60000);
        expect(sources).toHaveLength(1);
        expect(sources[0].closed).toBe(false);
    });

    it("reopens a refused stream (revoked link, catapult-api restarting) every 30s while OBS is connected", () => {
        vi.useFakeTimers();
        obs("connected");
        sources[0].readyState = FakeEventSource.CLOSED;
        sources[0].listeners.error({});
        vi.advanceTimersByTime(29999);
        expect(sources).toHaveLength(1);
        vi.advanceTimersByTime(1);
        expect(sources).toHaveLength(2);

        sources[1].readyState = FakeEventSource.CLOSED;
        sources[1].listeners.error({});
        obs("disconnected");
        vi.advanceTimersByTime(60000);
        expect(sources).toHaveLength(2);
    });

    it("re-emits each dispatch for twitchat.js", () => {
        const received = vi.fn();
        document.addEventListener("catapult:twitchat:dispatch", (event) => received(event.detail));
        obs("connected");
        sources[0].listeners.TwitchatDispatch({ data: "{\"id\":\"d-1\",\"payloads\":[]}" });
        expect(received).toHaveBeenCalledWith({ id: "d-1", payloads: [] });
    });

    it("closes the stream when OBS disconnects, and reopens it on reconnect", () => {
        obs("connected");
        obs("disconnected");
        expect(sources[0].closed).toBe(true);
        obs("connected");
        expect(sources).toHaveLength(2);
    });

    it("ignores a malformed message", () => {
        const received = vi.fn();
        document.addEventListener("catapult:twitchat:dispatch", received);
        obs("connected");
        vi.spyOn(console, "warn").mockImplementation(() => {});
        sources[0].listeners.TwitchatDispatch({ data: "{oops" });
        expect(received).not.toHaveBeenCalled();
    });
});
