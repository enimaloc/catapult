import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render } from "./helpers.js";

/** Minimal EventSource stand-in recording every instance and listener. */
class FakeEventSource {
    static CLOSED = 2;
    static instances = [];

    constructor(url) {
        this.url = url;
        this.listeners = {};
        this.closed = false;
        this.readyState = 0;
        FakeEventSource.instances.push(this);
    }

    addEventListener(type, listener) {
        (this.listeners[type] ??= []).push(listener);
    }

    close() {
        this.closed = true;
    }

    emit(type, data) {
        for (const listener of this.listeners[type] ?? []) listener({ data: JSON.stringify(data) });
    }
}

const sources = () => FakeEventSource.instances;
const current = () => sources().at(-1);

beforeEach(async () => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource);
    history.replaceState(null, "", "/channel/enimaloc");
    window.CatapultCsrf = { postJson: vi.fn() };
    await load("visibility", "channel-page", "channel-events");
});

describe("connection lifecycle", () => {
    it("opens one stream for the channel being viewed", () => {
        render();

        expect(sources()).toHaveLength(1);
        expect(current().url).toBe("/events/channel/enimaloc");
    });

    it("reuses the stream across re-renders of the same channel", () => {
        render();
        render();

        expect(sources()).toHaveLength(1);
    });

    it("switches streams when moving to another channel", () => {
        render();
        history.replaceState(null, "", "/channel/other");
        render();

        expect(sources()).toHaveLength(2);
        expect(sources()[0].closed).toBe(true);
        expect(current().url).toBe("/events/channel/other");
    });

    it("closes the stream when leaving the channel pages", () => {
        render();
        history.replaceState(null, "", "/privacy");
        render();

        expect(sources()[0].closed).toBe(true);
    });

    it("does not open anything outside the channel pages", () => {
        history.replaceState(null, "", "/channels");
        render();

        expect(sources()).toHaveLength(0);
    });
});

describe("subscriptions", () => {
    it("subscribes to the events the page references plus the custom-handled ones", () => {
        html(`<span data-on="live:ChannelLiveStateEvent.state"></span>`);

        render();

        expect(Object.keys(current().listeners).filter(name => name !== "open" && name !== "error").sort())
            .toEqual(["BindingDeletedEvent", "ChannelConnectionChangedEvent", "ChannelLiveStateEvent",
                "ChannelSettingsUpdatedEvent", "DtddMappingChangedEvent", "GameChangedEvent"]);
    });

    it("subscribes each event only once, adding newly referenced ones on re-render", () => {
        html(`<span data-on="live:ChannelLiveStateEvent.state"></span>`);
        render();
        html(`<span data-on="live:ChannelLiveStateEvent.state"></span><i data-value="textContent:BotStateChangedEvent.state"></i>`);
        render();

        expect(current().listeners.ChannelLiveStateEvent).toHaveLength(1);
        expect(current().listeners.BotStateChangedEvent).toHaveLength(1);
    });

    it("starts from a clean subscription list on a new stream", () => {
        html(`<span data-on="live:ChannelLiveStateEvent.state"></span>`);
        render();
        history.replaceState(null, "", "/channel/other");
        render();

        expect(current().listeners.ChannelLiveStateEvent).toHaveLength(1);
    });
});

describe("event handling", () => {
    it("feeds every event to Visibility.dispatch", () => {
        html(`<span id="live" class="hidden" data-if="live" data-on="live:ChannelLiveStateEvent.state"></span>`);
        render();

        current().emit("ChannelLiveStateEvent", { state: true });

        expect(document.getElementById("live").classList.contains("hidden")).toBe(false);
    });

    it("moves the accordion to the new current game", () => {
        html(`<ul id="channel-bindings-list"><mdui-collapse></mdui-collapse></ul>`);
        render();

        current().emit("GameChangedEvent", { bindingId: "b7", sourceName: "X" });

        expect(document.querySelector("mdui-collapse").value).toBe("b7");
    });

    it("removes deleted bindings from the list", () => {
        html(`<ul id="channel-bindings-list"><mdui-collapse><li data-binding-id="b1"></li><li data-binding-id="b2"></li></mdui-collapse></ul>`);
        render();

        current().emit("BindingDeletedEvent", { bindingId: "b1" });

        expect(document.querySelector("[data-binding-id=b1]")).toBeNull();
        expect(document.querySelector("[data-binding-id=b2]")).not.toBeNull();
    });
});

describe("events the page catches up on", () => {
    beforeEach(() => {
        window.CatapultSpa = { navigate: vi.fn(async () => {}) };
        window.scrollTo = vi.fn();
    });

    it("hands the settings to the settings cards", () => {
        window.CatapultSettings = { apply: vi.fn() };
        render();
        const settings = { ccl: { enabled: true, blockedCcls: ["Gambling"] } };

        current().emit("ChannelSettingsUpdatedEvent", { username: "enimaloc", settings });

        expect(CatapultSettings.apply).toHaveBeenCalledWith(settings);
        delete window.CatapultSettings;
    });

    it("re-renders the page when a DTDD mapping changed", async () => {
        render();

        current().emit("DtddMappingChangedEvent", { username: "enimaloc" });
        await flush();

        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc", false, "");
    });

    it("re-renders the page for a connection change, but Steam's, whose card follows its own event", async () => {
        render();

        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "STEAM", connected: true });
        await flush();
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();

        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "XBOX", connected: false });
        await flush();
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
    });
});

describe("reconnection", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        window.CatapultSpa = { navigate: vi.fn(async () => {}) };
        window.scrollTo = vi.fn();
    });

    function closeByServer(source) {
        source.readyState = FakeEventSource.CLOSED;
        source.emit("error", {});
    }

    it("reopens a stream that stopped for good, after a growing delay", () => {
        render();
        closeByServer(current());
        vi.advanceTimersByTime(4999);
        expect(sources()).toHaveLength(1);
        vi.advanceTimersByTime(1);
        expect(sources()).toHaveLength(2);
        expect(current().url).toBe("/events/channel/enimaloc");

        closeByServer(current());
        vi.advanceTimersByTime(9999);
        expect(sources()).toHaveLength(2);
        vi.advanceTimersByTime(1);
        expect(sources()).toHaveLength(3);
    });

    // The server ends every stream after 30 minutes (after its ":open" comment, which EventSource
    // ignores): the browser reconnects by itself, and the page catches up once the new one opens.
    it("catches up after a stream the server ended cleanly reconnects by itself", async () => {
        render();
        current().emit("open", {});
        current().emit("error", {});
        vi.advanceTimersByTime(60000);
        expect(sources()).toHaveLength(1);

        current().emit("open", {});
        await vi.runAllTimersAsync();
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
        expect(sources()).toHaveLength(1);
    });

    it("leaves a stream reconnecting by itself alone", () => {
        render();
        current().emit("error", {});
        vi.advanceTimersByTime(60000);

        expect(sources()).toHaveLength(1);
    });

    it("subscribes the reopened stream to the page's events", () => {
        html(`<span data-on="live:ChannelLiveStateEvent.state"></span>`);
        render();
        closeByServer(current());
        vi.advanceTimersByTime(5000);

        expect(current().listeners.ChannelLiveStateEvent).toHaveLength(1);
    });

    it("re-renders the page to catch up each time the stream opens again, not the first time", async () => {
        render();
        current().emit("open", {});
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();

        current().emit("open", {});
        await vi.runAllTimersAsync();
        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc", false, "");

        closeByServer(current());
        vi.advanceTimersByTime(5000);
        current().emit("open", {});
        await vi.runAllTimersAsync();
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(2);
    });

    it("stops trying once the visitor left the channel", () => {
        render();
        closeByServer(current());
        history.replaceState(null, "", "/privacy");
        render();
        vi.advanceTimersByTime(60000);

        expect(sources()).toHaveLength(1);
    });

    it("ignores the events of a stream it replaced", () => {
        render();
        const old = current();
        history.replaceState(null, "", "/channel/other");
        render();
        closeByServer(old);
        old.emit("open", {});
        vi.advanceTimersByTime(60000);

        expect(sources()).toHaveLength(2);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });
});
