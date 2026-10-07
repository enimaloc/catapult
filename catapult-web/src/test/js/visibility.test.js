import { beforeEach, describe, expect, it } from "vitest";
import { html, load } from "./helpers.js";

const hidden = el => el.classList.contains("hidden");
const $ = selector => document.querySelector(selector);

beforeEach(async () => {
    await load("visibility");
});

describe("_resolve", () => {
    const resolve = (data, spec) => Visibility._resolve(data, spec);

    it("resolves a bare event name to true, and its negation to false", () => {
        expect(resolve({}, "GameChangedEvent")).toBe(true);
        expect(resolve({}, "!GameChangedEvent")).toBe(false);
    });

    it("returns a plain field verbatim", () => {
        expect(resolve({ sourceName: "Valheim" }, "GameChangedEvent.sourceName")).toBe("Valheim");
        expect(resolve({ count: 3 }, "E.count")).toBe(3);
        expect(resolve({}, "E.missing")).toBeUndefined();
    });

    it("negates a field as a boolean, arrays counting as truthy only when non-empty", () => {
        expect(resolve({ state: true }, "E.!state")).toBe(false);
        expect(resolve({ state: false }, "E.!state")).toBe(true);
        expect(resolve({ tws: [] }, "E.!tws")).toBe(true);
        expect(resolve({ tws: ["x"] }, "E.!tws")).toBe(false);
    });

    it("matches a field against one or several literals", () => {
        expect(resolve({ status: "PENDING" }, "E.status=PENDING")).toBe(true);
        expect(resolve({ status: "ACCEPTED" }, "E.status=PENDING")).toBe(false);
        expect(resolve({ status: "REMOVED" }, "E.status=PENDING/REMOVED")).toBe(true);
        expect(resolve({ status: "ACCEPTED" }, "E.!status=PENDING/REMOVED")).toBe(true);
    });

    it("substitutes the default when the resolved value is falsy", () => {
        expect(resolve({ twitchGameName: null }, "E.twitchGameName|—")).toBe("—");
        expect(resolve({ twitchGameName: "Doom" }, "E.twitchGameName|—")).toBe("Doom");
    });

    it("treats a missing payload as absent fields", () => {
        expect(resolve(undefined, "E.field")).toBeUndefined();
        expect(resolve(null, "E.field=x")).toBe(false);
    });
});

describe("_eventNameOf", () => {
    it.each([
        ["GameChangedEvent", "GameChangedEvent"],
        ["!GameChangedEvent", "GameChangedEvent"],
        ["GameChangedEvent.sourceName", "GameChangedEvent"],
        ["!E.status=A/B|x", "E"],
    ])("%s names %s", (spec, name) => {
        expect(Visibility._eventNameOf(spec)).toBe(name);
    });
});

describe("discoverEventNames", () => {
    it("collects every distinct event referenced by spa:* breadcrumbs", () => {
        html(`
            <div data-on="live:ChannelLiveStateEvent.state,offline:ChannelLiveStateEvent.!state"></div>
            <div data-switch="a:MinecraftEnrollEvent.status=NONE,b:!MinecraftDisconnectedEvent"></div>
            <span data-value="textContent:GameChangedEvent.sourceName"></span>
            <input data-in="checked:BindingUpdatedEvent.ccls">`);

        expect([...Visibility.discoverEventNames(document)].sort()).toEqual([
            "BindingUpdatedEvent", "ChannelLiveStateEvent", "GameChangedEvent",
            "MinecraftDisconnectedEvent", "MinecraftEnrollEvent",
        ]);
    });

    it("returns an empty set when nothing references an event", () => {
        html("<div data-if='x'></div>");
        expect(Visibility.discoverEventNames(document).size).toBe(0);
    });
});

describe("apply", () => {
    it("shows an element only when every flag it names is true", () => {
        html(`<p id="a" data-if="connected,rateLimited" class="hidden"></p>`);

        Visibility.apply(document, { connected: true, rateLimited: true });
        expect(hidden($("#a"))).toBe(false);

        Visibility.apply(document, { connected: true, rateLimited: false });
        expect(hidden($("#a"))).toBe(true);
    });

    it("leaves an element untouched while one of its flags is still unknown", () => {
        html(`<p id="a" data-if="connected,rateLimited"></p>`);

        Visibility.apply(document, { connected: false, rateLimited: undefined });
        expect(hidden($("#a"))).toBe(false);
    });
});

describe("dispatch", () => {
    it("toggles data-on elements from the event payload", () => {
        html(`
            <span id="live" data-if="live" data-on="live:ChannelLiveStateEvent.state" class="hidden"></span>
            <span id="off" data-if="offline" data-on="offline:ChannelLiveStateEvent.!state"></span>`);

        Visibility.dispatch("ChannelLiveStateEvent", { state: true });

        expect(hidden($("#live"))).toBe(false);
        expect(hidden($("#off"))).toBe(true);
    });

    it("ignores events no breadcrumb names", () => {
        html(`<span id="live" data-if="live" data-on="live:ChannelLiveStateEvent.state" class="hidden"></span>`);

        Visibility.dispatch("OtherEvent", { state: true });

        expect(hidden($("#live"))).toBe(true);
    });

    it("keeps flags from earlier events, so multi-flag elements combine them", () => {
        html(`<p id="p" data-if="connected,privateProfile"
                 data-on="connected:SteamConnectionStateEvent.connected,privateProfile:SteamConnectionStateEvent.privateProfile"
                 class="hidden"></p>`);

        Visibility.dispatch("SteamConnectionStateEvent", { connected: true, privateProfile: true });
        expect(hidden($("#p"))).toBe(false);

        Visibility.dispatch("SteamConnectionStateEvent", { connected: true, privateProfile: false });
        expect(hidden($("#p"))).toBe(true);
    });

    it("drives data-switch cases, a bare event name forcing its case on", () => {
        html(`
            <div id="none" data-if="caseNone"
                 data-switch="caseNone:MinecraftEnrollEvent.status=NONE,caseNone:MinecraftDisconnectedEvent"></div>
            <div id="pending" data-if="casePending" class="hidden"
                 data-switch="casePending:MinecraftEnrollEvent.status=PENDING,casePending:!MinecraftDisconnectedEvent"></div>`);

        Visibility.dispatch("MinecraftEnrollEvent", { status: "PENDING" });
        expect(hidden($("#none"))).toBe(true);
        expect(hidden($("#pending"))).toBe(false);

        Visibility.dispatch("MinecraftDisconnectedEvent", {});
        expect(hidden($("#none"))).toBe(false);
        expect(hidden($("#pending"))).toBe(true);
    });

    it("scopes elements inside a binding row to that row's events", () => {
        html(`
            <div data-binding-id="b1">
                <button id="r1" data-if="hasOverride" data-on="hasOverride:TwUpdatedEvent.tws"></button>
            </div>
            <div data-binding-id="b2">
                <button id="r2" data-if="hasOverride" data-on="hasOverride:TwUpdatedEvent.tws"></button>
            </div>`);

        Visibility.dispatch("TwUpdatedEvent", { bindingId: "b1", tws: [] });

        expect(hidden($("#r1"))).toBe(true);
        expect(hidden($("#r2"))).toBe(false);
    });

    it("skips row-scoped elements when the payload carries no data", () => {
        html(`<div data-binding-id="b1"><span id="s" data-value="textContent:E.name">old</span></div>`);

        Visibility.dispatch("E", null);

        expect($("#s").textContent).toBe("old");
    });

    it("updates page-level elements whatever the payload's bindingId", () => {
        html(`<span id="game" data-value="textContent:GameChangedEvent.sourceName">old</span>`);

        Visibility.dispatch("GameChangedEvent", { bindingId: "b9", sourceName: "Valheim" });

        expect($("#game").textContent).toBe("Valheim");
    });

    it("assigns data-value properties, with a default for empty values", () => {
        html(`
            <div data-binding-id="b1">
                <span id="name" data-value="textContent:BindingUpdatedEvent.twitchGameName|—">x</span>
            </div>
            <input id="bot" type="checkbox" data-value="checked:BotStateChangedEvent.state">`);

        Visibility.dispatch("BindingUpdatedEvent", { bindingId: "b1", twitchGameName: null });
        Visibility.dispatch("BotStateChangedEvent", { state: true });

        expect($("#name").textContent).toBe("—");
        expect($("#bot").checked).toBe(true);
    });

    it("sets data-in properties from array membership of the element's value", () => {
        html(`
            <div data-binding-id="b1">
                <input id="drugs" type="checkbox" value="Drugs" data-in="checked:BindingUpdatedEvent.ccls">
                <input id="gore" type="checkbox" value="Gore" checked data-in="checked:BindingUpdatedEvent.ccls">
            </div>`);

        Visibility.dispatch("BindingUpdatedEvent", { bindingId: "b1", ccls: ["Drugs"] });

        expect($("#drugs").checked).toBe(true);
        expect($("#gore").checked).toBe(false);
    });

    it("treats an absent array field as non-membership", () => {
        html(`<input id="tw" type="checkbox" value="x" checked data-in="checked:TwResetEvent.tws">`);

        Visibility.dispatch("TwResetEvent", { bindingId: undefined });

        expect($("#tw").checked).toBe(false);
    });
});
