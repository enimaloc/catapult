/*
 * twitchat-stream.js: the owner's dispatch stream is open exactly while OBS is, and each
 * TwitchatDispatch it carries becomes a catapult:twitchat:dispatch for twitchat.js.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load } from "./helpers.js";

let sources;

class FakeEventSource {
    constructor(url) {
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

    it("opens the page's events URL, /me/twitchat/events by default", async () => {
        obs("connected");
        expect(sources.at(-1).url).toBe("/me/twitchat/events");
        obs("disconnected");

        document.body.dataset.twitchatEventsUrl = "/widget/obs/t-1/events";
        try {
            sources = [];
            await load("twitchat-stream");
            obs("connected");
            expect(sources.map((s) => s.url)).toContain("/widget/obs/t-1/events");
        } finally {
            delete document.body.dataset.twitchatEventsUrl;
        }
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
