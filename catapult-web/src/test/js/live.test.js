// catapult-web/src/test/js/live.test.js
/* live.js: the page's single EventSource on /events/nav, shared through CatapultLive. */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load } from "./helpers.js";

/** Minimal EventSource stand-in recording every instance and a way to fire its events. */
class FakeEventSource {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;
    static instances = [];
    constructor(url) {
        this.url = url;
        this.listeners = {};
        this.readyState = FakeEventSource.CONNECTING;
        this.closed = false;
        FakeEventSource.instances.push(this);
    }
    close() {
        this.closed = true;
        this.readyState = FakeEventSource.CLOSED;
    }
    /** The browser giving up: a reconnect answered with something other than a stream. */
    giveUp() {
        this.readyState = FakeEventSource.CLOSED;
        this.fire("error");
    }
    addEventListener(type, listener) {
        (this.listeners[type] ??= []).push(listener);
    }
    fire(type, data) {
        (this.listeners[type] ?? []).forEach(listener => listener({ data }));
    }
}

function setVisibility(state) {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
    document.dispatchEvent(new Event("visibilitychange"));
}

afterEach(() => {
    vi.useRealTimers();
    delete document.visibilityState;
});

beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource);
    delete window.CatapultLive;
});

describe("live.js", () => {
    it("opens exactly one stream on /events/nav", async () => {
        await load("live");
        expect(FakeEventSource.instances.map(s => s.url)).toEqual(["/events/nav"]);
    });

    it("hands a named event's parsed data to its listeners", async () => {
        await load("live");
        const fn = vi.fn();
        window.CatapultLive.on("Broadcast", fn);
        FakeEventSource.instances[0].fire("Broadcast", '{"id":"b1"}');
        expect(fn).toHaveBeenCalledWith({ id: "b1" });
    });

    it("ignores invalid JSON", async () => {
        await load("live");
        const fn = vi.fn();
        window.CatapultLive.on("Broadcast", fn);
        expect(() => FakeEventSource.instances[0].fire("Broadcast", "{nope")).not.toThrow();
        expect(fn).not.toHaveBeenCalled();
    });

    it("keeps calling the other listeners when one throws", async () => {
        await load("live");
        vi.spyOn(console, "error").mockImplementation(() => {});
        const next = vi.fn();
        window.CatapultLive.on("Broadcast", () => { throw new Error("boom"); });
        window.CatapultLive.on("Broadcast", next);
        FakeEventSource.instances[0].fire("Broadcast", "{}");
        expect(next).toHaveBeenCalled();
    });

    it("fires onOpen every time and onReopen only after the first open", async () => {
        await load("live");
        const open = vi.fn();
        const reopen = vi.fn();
        window.CatapultLive.onOpen(open);
        window.CatapultLive.onReopen(reopen);
        FakeEventSource.instances[0].fire("open", "");
        expect(open).toHaveBeenCalledTimes(1);
        expect(reopen).not.toHaveBeenCalled();
        FakeEventSource.instances[0].fire("open", "");
        expect(open).toHaveBeenCalledTimes(2);
        expect(reopen).toHaveBeenCalledTimes(1);
    });

    it("does nothing without EventSource", async () => {
        vi.stubGlobal("EventSource", undefined);
        await load("live");
        expect(() => {
            window.CatapultLive.on("Broadcast", () => {});
            window.CatapultLive.onReopen(() => {});
            window.CatapultLive.onOpen(() => {});
        }).not.toThrow();
        expect(FakeEventSource.instances).toEqual([]);
    });

    it("rebuilds a source the browser gave up on, after a backoff, with the same listeners", async () => {
        vi.useFakeTimers();
        await load("live");
        const fn = vi.fn();
        const reopen = vi.fn();
        const open = vi.fn();
        window.CatapultLive.on("Broadcast", fn);
        window.CatapultLive.onReopen(reopen);
        window.CatapultLive.onOpen(open);
        FakeEventSource.instances[0].fire("open", "");

        FakeEventSource.instances[0].giveUp();
        vi.advanceTimersByTime(4999);
        expect(FakeEventSource.instances).toHaveLength(1);
        vi.advanceTimersByTime(1);
        expect(FakeEventSource.instances).toHaveLength(2);
        const next = FakeEventSource.instances[1];
        expect(next.url).toBe("/events/nav");

        next.fire("open", "");
        expect(reopen).toHaveBeenCalledTimes(1);
        expect(open).toHaveBeenCalledTimes(2);
        next.fire("Broadcast", '{"id":"b2"}');
        expect(fn).toHaveBeenCalledTimes(1);
        expect(fn).toHaveBeenCalledWith({ id: "b2" });
    });

    it("ignores a transient error the browser retries by itself", async () => {
        vi.useFakeTimers();
        await load("live");
        FakeEventSource.instances[0].fire("error");
        vi.advanceTimersByTime(120000);
        expect(FakeEventSource.instances).toHaveLength(1);
    });

    it("doubles the backoff up to a minute, and starts again from 5 s once a stream opens", async () => {
        vi.useFakeTimers();
        await load("live");
        const delays = [];
        for (let i = 0; i < 6; i++) {
            const before = FakeEventSource.instances.length;
            FakeEventSource.instances.at(-1).giveUp();
            let waited = 0;
            while (FakeEventSource.instances.length === before && waited < 120000) { vi.advanceTimersByTime(1000); waited += 1000; }
            delays.push(waited);
        }
        expect(delays).toEqual([5000, 10000, 20000, 40000, 60000, 60000]);

        FakeEventSource.instances.at(-1).fire("open", "");
        FakeEventSource.instances.at(-1).giveUp();
        vi.advanceTimersByTime(5000);
        expect(FakeEventSource.instances).toHaveLength(8);
    });

    it("closes an evicted stream and reopens it only once the tab is shown again", async () => {
        vi.useFakeTimers();
        await load("live");
        const reopen = vi.fn();
        const fn = vi.fn();
        window.CatapultLive.on("Broadcast", fn);
        window.CatapultLive.onReopen(reopen);
        const evicted = FakeEventSource.instances[0];
        evicted.fire("open", "");

        evicted.fire("Evicted", "{}");
        expect(evicted.closed).toBe(true);
        evicted.fire("error");
        vi.advanceTimersByTime(600000);
        setVisibility("hidden");
        expect(FakeEventSource.instances).toHaveLength(1);

        setVisibility("visible");
        expect(FakeEventSource.instances).toHaveLength(2);
        FakeEventSource.instances[1].fire("open", "");
        expect(reopen).toHaveBeenCalledTimes(1);
        FakeEventSource.instances[1].fire("Broadcast", "{}");
        expect(fn).toHaveBeenCalledTimes(1);

        setVisibility("visible"); // not evicted any more: nothing new
        expect(FakeEventSource.instances).toHaveLength(2);
    });
});
