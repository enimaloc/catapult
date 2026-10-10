import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render } from "./helpers.js";

/** Minimal EventSource stand-in recording every instance and listener. */
class FakeEventSource {
    static instances = [];

    constructor(url) {
        this.url = url;
        this.listeners = {};
        this.closed = false;
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

        expect(Object.keys(current().listeners).sort())
            .toEqual(["BindingDeletedEvent", "ChannelLiveStateEvent", "GameChangedEvent"]);
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
