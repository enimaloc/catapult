// catapult-web/src/test/js/live.test.js
/* live.js: the page's single EventSource on /events/nav, shared through CatapultLive. */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { load } from "./helpers.js";

/** Minimal EventSource stand-in recording every instance and a way to fire its events. */
class FakeEventSource {
    static instances = [];
    constructor(url) {
        this.url = url;
        this.listeners = {};
        FakeEventSource.instances.push(this);
    }
    addEventListener(type, listener) {
        (this.listeners[type] ??= []).push(listener);
    }
    fire(type, data) {
        (this.listeners[type] ?? []).forEach(listener => listener({ data }));
    }
}

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
});
