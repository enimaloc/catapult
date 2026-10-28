import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

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
                "ChannelSettingsUpdatedEvent", "CustomBindingChangedEvent", "CustomBindingsUpdatedEvent",
                "DtddMappingChangedEvent", "GameChangedEvent"]);
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

    it("hands a DTDD mapping's change to its card, re-rendering the page when the card can't show it", async () => {
        window.CatapultDtdd = { apply: vi.fn(() => true) };
        render();
        const change = { username: "enimaloc", igdbId: "42", current: null, hasPending: false };

        current().emit("DtddMappingChangedEvent", change);
        await flush();
        expect(CatapultDtdd.apply).toHaveBeenCalledWith(change);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();

        CatapultDtdd.apply.mockReturnValue(false);
        current().emit("DtddMappingChangedEvent", { username: "enimaloc" });
        await flush();
        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc", false, "", { fresh: true });
        delete window.CatapultDtdd;
    });

    it("hands the active custom binding to the overview card", () => {
        window.CatapultCustomBindings = { setActive: vi.fn() };
        render();

        current().emit("CustomBindingChangedEvent", { username: "enimaloc", activeId: "b1", ts: 1000 });
        current().emit("CustomBindingChangedEvent", { username: "enimaloc", activeId: null });

        // with when it was published, for the card to drop one arriving after a newer one
        expect(CatapultCustomBindings.setActive.mock.calls).toEqual([["b1", 1000], [null, undefined]]);
    });

    it("hands the custom bindings' list to the overview card, re-rendering the page when it can't take it", async () => {
        window.CatapultCustomBindings = { setActive: vi.fn(), apply: vi.fn(() => true) };
        render();
        const bindings = [{ id: "b1", name: "Art" }];

        current().emit("CustomBindingsUpdatedEvent", { username: "enimaloc", bindings, activeId: "b1", ts: 1000 });
        await flush();
        expect(CatapultCustomBindings.apply).toHaveBeenCalledWith(bindings, "b1", 1000);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();

        CatapultCustomBindings.apply.mockReturnValue(false);
        current().emit("CustomBindingsUpdatedEvent", { username: "enimaloc" });
        await flush();
        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc", false, "", { fresh: true });
        delete window.CatapultCustomBindings;
    });

    it("flips the Xbox card in place, leaves Steam's to its own event, re-renders for any other provider", async () => {
        window.CatapultConnections = { showXboxConnected: vi.fn() };
        render();

        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "STEAM", connected: true });
        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "XBOX", connected: true });
        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "XBOX", connected: false });
        await flush();
        expect(CatapultConnections.showXboxConnected.mock.calls).toEqual([[true], [false]]);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();

        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "TWITCH", connected: false });
        await flush();
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
        delete window.CatapultConnections;
    });
});

describe("events patched in place by their cards", () => {
    const DTDD = `
        <mdui-card id="channel-dtdd-mapping-card" data-igdb-id="42">
            <div id="dtdd-current"><span id="dtdd-name">Valheim</span>
                <mdui-chip id="dtdd-confidence">80%</mdui-chip><mdui-chip id="dtdd-verified" class="hidden"></mdui-chip></div>
            <div id="dtdd-no-mapping" class="hidden"></div>
            <mdui-button id="dtdd-validate-btn"></mdui-button>
            <mdui-button id="dtdd-correct-btn"></mdui-button>
            <p id="dtdd-proposed" class="hidden"></p>
            <div id="dtdd-search-dialog" hidden><input id="dtdd-search-input"/><mdui-list id="dtdd-search-results"></mdui-list></div>
        </mdui-card>`;
    const XBOX = `
        <div id="channel-connections"><mdui-card data-conn="xbox">
            <mdui-chip class="xbox-connected-chip hidden"></mdui-chip><mdui-chip class="xbox-not-connected-chip"></mdui-chip>
            <mdui-button id="xbox-connect-btn"></mdui-button><mdui-button id="xbox-disconnect-btn" class="hidden"></mdui-button>
        </mdui-card></div>`;
    const hidden = id => document.getElementById(id).classList.contains("hidden");
    const mapping = (current, hasPending) => ({ username: "enimaloc", igdbId: "42", current, hasPending });

    beforeEach(async () => {
        window.CatapultSpa = { navigate: vi.fn(async () => {}) };
        window.scrollTo = vi.fn();
        window.GameSearch = { attach: vi.fn(() => ({ selected: () => null, select: vi.fn() })) };
        await load("visibility", "channel-page", "channel-dtdd", "channel-connections", "channel-custom-bindings",
            "channel-events");
    });

    it("patches the custom bindings' list, an open dialog and what is being typed left as they are", async () => {
        const row = (id, name) => `
            <mdui-collapse-item class="custom-binding-item"${id ? ` data-custom-binding-id="${id}"` : ""}>
                <span class="custom-binding-name-label">${name}</span><span class="custom-binding-game-label">—</span>
                <span class="custom-binding-active-chip hidden"></span><span class="custom-binding-uninterruptible-chip hidden"></span>
                <input class="custom-binding-name" value="${name}"/><input class="custom-binding-game-input"/>
                <input type="hidden" class="custom-binding-game-id"/><mdui-list class="custom-binding-game-results"></mdui-list>
                <mdui-switch class="custom-binding-uninterruptible"></mdui-switch>
                <mdui-button class="custom-binding-activate"></mdui-button><mdui-button class="custom-binding-stop hidden"></mdui-button>
                <mdui-button class="custom-binding-save"></mdui-button><mdui-button class="custom-binding-delete"></mdui-button>
            </mdui-collapse-item>`;
        html(`
            <mdui-list id="channel-custom-bindings-list"><mdui-collapse>${row("c1", "Art")}${row("c2", "Dev")}
                <div class="custom-binding-new hidden">${row("", "")}</div>
            </mdui-collapse><mdui-list-item id="channel-custom-bindings-empty" class="hidden"></mdui-list-item></mdui-list>
            <template id="custom-binding-item-template">${row("", "")}</template>
            <p id="custom-bindings-error" class="hidden"></p>
            <mdui-dialog id="custom-binding-delete-dialog"><mdui-button class="custom-binding-delete-confirm"></mdui-button></mdui-dialog>`);
        render();
        const typing = document.querySelector("[data-custom-binding-id=c2] .custom-binding-name");
        typing.focus();
        typing.value = "Dev en cours";
        document.querySelector("[data-custom-binding-id=c1] .custom-binding-delete").click();
        const dialog = document.getElementById("custom-binding-delete-dialog");

        current().emit("CustomBindingsUpdatedEvent", { username: "enimaloc", activeId: "c3", bindings: [
            { id: "c1", name: "Art", twitchGameName: "Art" },
            { id: "c2", name: "Dev", twitchGameName: "Software" },
            { id: "c3", name: "Jeux", twitchGameName: "Games" }] });
        await flush();

        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
        expect(dialog.open).toBe(true);
        expect(typing.value).toBe("Dev en cours");
        expect(document.activeElement).toBe(typing);
        expect(document.querySelector("[data-custom-binding-id=c2] .custom-binding-game-label").textContent).toBe("Software");
        expect(document.querySelector("[data-custom-binding-id=c3] .custom-binding-name-label").textContent).toBe("Jeux");
        expect(document.querySelector("[data-custom-binding-id=c3]").classList.contains("is-active")).toBe(true);
    });

    it("shows a DTDD mapping's change on its card", async () => {
        html(DTDD);
        render();

        current().emit("DtddMappingChangedEvent", mapping({ dtddId: 9, name: "Valheim (2021)", confidence: 1, verified: true }, false));
        await flush();

        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
        expect(document.getElementById("dtdd-name").textContent).toBe("Valheim (2021)");
        expect(document.getElementById("dtdd-confidence").textContent).toBe("100%");
        expect(hidden("dtdd-verified")).toBe(false);
        expect(hidden("dtdd-validate-btn")).toBe(true);
        expect(hidden("dtdd-proposed")).toBe(true);

        current().emit("DtddMappingChangedEvent", mapping(null, false));
        await flush();
        expect(hidden("dtdd-current")).toBe(true);
        expect(hidden("dtdd-no-mapping")).toBe(false);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("offers the validation again once an unverified mapping has no proposal pending", async () => {
        html(DTDD);
        document.getElementById("dtdd-validate-btn").classList.add("hidden");
        render();

        current().emit("DtddMappingChangedEvent", mapping({ dtddId: 9, name: "Valheim", confidence: 0.8, verified: false }, false));
        await flush();

        expect(hidden("dtdd-validate-btn")).toBe(false);
    });

    it("shows the viewer's own state when the page's state carries it", async () => {
        html(DTDD);
        render();

        current().emit("DtddMappingChangedEvent", { ...mapping({ dtddId: 9, name: "Valheim", confidence: 0.8 }, null),
            proposed: true, validatable: false });
        await flush();

        expect(hidden("dtdd-proposed")).toBe(false);
        expect(hidden("dtdd-validate-btn")).toBe(true);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it.each([
        ["carries nothing but the channel (an older catapult-api)", { username: "enimaloc" }, DTDD],
        ["is for another game than the card's", { ...mapping(null, false), igdbId: "7" }, DTDD],
        ["finds no card", mapping(null, false), ""],
        ["leaves proposals pending, maybe the viewer's", mapping(null, true), DTDD],
    ])("re-renders the page for a DTDD change that %s", async (_, data, page) => {
        html(page);
        render();

        current().emit("DtddMappingChangedEvent", data);
        await flush();

        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
    });

    it("flips the Xbox card to the connection's state", async () => {
        html(XBOX);
        render();

        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "XBOX", connected: true });
        await flush();

        expect(document.querySelector(".xbox-connected-chip").classList.contains("hidden")).toBe(false);
        expect(document.querySelector(".xbox-not-connected-chip").classList.contains("hidden")).toBe(true);
        expect(hidden("xbox-connect-btn")).toBe(true);
        expect(hidden("xbox-disconnect-btn")).toBe(false);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();

        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "XBOX", connected: false });
        await flush();
        expect(hidden("xbox-connect-btn")).toBe(false);
        expect(hidden("xbox-disconnect-btn")).toBe(true);
    });

    it("re-renders the page for an Xbox change without its state", async () => {
        html(XBOX);
        render();

        current().emit("ChannelConnectionChangedEvent", { username: "enimaloc", provider: "XBOX" });
        await flush();

        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
    });
});

describe("reconnection", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.spyOn(Math, "random").mockReturnValue(0.5); // no jitter
        window.CatapultSpa = { navigate: vi.fn(async () => {}) };
        window.scrollTo = vi.fn();
        vi.stubGlobal("fetch", vi.fn(async () => response(200, [])));
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

    it("reopens up to 2 s earlier or later at random, so that the pages closed together don't reopen together", () => {
        Math.random.mockReturnValue(0);
        render();
        closeByServer(current());
        vi.advanceTimersByTime(2999);
        expect(sources()).toHaveLength(1);
        vi.advanceTimersByTime(1);
        expect(sources()).toHaveLength(2);

        Math.random.mockReturnValue(0.999);
        closeByServer(current());
        vi.advanceTimersByTime(11995);
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
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
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

    it("catches up on the channel's state each time the stream opens again, not the first time", async () => {
        render();
        current().emit("open", {});
        expect(fetch).not.toHaveBeenCalled();

        current().emit("open", {});
        await vi.runAllTimersAsync();
        expect(fetch).toHaveBeenCalledWith("/spa/channel/enimaloc/state", { headers: { Accept: "application/json" } });

        closeByServer(current());
        vi.advanceTimersByTime(5000);
        current().emit("open", {});
        await vi.runAllTimersAsync();
        expect(fetch).toHaveBeenCalledTimes(2);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
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

describe("catching up on the channel's state", () => {
    const BINDINGS = `<mdui-list id="channel-bindings-list"><mdui-collapse>
        <mdui-collapse-item data-binding-id="b1">
            <span class="binding-game-name" data-value="textContent:BindingUpdatedEvent.twitchGameName|—">Old</span>
            <mdui-switch class="ccl" data-value="checked:CclStateEvent.enabled"></mdui-switch>
        </mdui-collapse-item>
    </mdui-collapse></mdui-list>`;
    const PAGE = `
        <mdui-chip id="live" class="hidden" data-if="live" data-on="live:ChannelLiveStateEvent.state"></mdui-chip>
        <mdui-switch id="bot" data-value="checked:BotStateChangedEvent.state"></mdui-switch>
        ${BINDINGS}`;
    const binding = (id, name, ccl) => [
        { name: "BindingUpdatedEvent", data: { bindingId: id, twitchGameId: "1", twitchGameName: name, ccls: [] } },
        { name: "CclStateEvent", data: { bindingId: id, enabled: ccl } },
    ];

    function reopen() {
        current().emit("open", {});
        current().emit("open", {});
    }

    beforeEach(() => {
        window.CatapultSpa = { navigate: vi.fn(async () => {}) };
        window.scrollTo = vi.fn();
    });

    it("applies the state as the events it is made of, in place: nothing added or removed", async () => {
        html(PAGE);
        const custom = { setActive: vi.fn(), apply: vi.fn(() => true) };
        window.CatapultCustomBindings = custom;
        const state = [
            { name: "ChannelLiveStateEvent", data: { state: true } },
            { name: "BotStateChangedEvent", data: { state: true } },
            ...binding("b1", "New name", true),
            { name: "CustomBindingsUpdatedEvent", data: { bindings: [{ id: "c1" }], activeId: null } },
        ];
        vi.stubGlobal("fetch", vi.fn(async () => response(200, state)));
        render();
        const row = document.querySelector("[data-binding-id=b1]");
        const changes = [];
        new MutationObserver(records => changes.push(...records)).observe(document.body, { childList: true, subtree: true });

        reopen();
        await flush();

        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
        expect(document.getElementById("live").classList.contains("hidden")).toBe(false);
        expect(document.getElementById("bot").checked).toBe(true);
        expect(row.querySelector(".ccl").checked).toBe(true);
        expect(row.querySelector(".binding-game-name").textContent).toBe("New name");
        expect(document.querySelector("[data-binding-id=b1]")).toBe(row);
        expect(custom.apply).toHaveBeenCalledWith([{ id: "c1" }], null, undefined);
        // Only the renamed binding's text
        expect(changes.flatMap(r => [...r.addedNodes, ...r.removedNodes]).every(n => n.nodeType === Node.TEXT_NODE))
            .toBe(true);
        delete window.CatapultCustomBindings;
    });

    it("asks for the page's own query: the bindings it lists", async () => {
        history.replaceState(null, "", "/channel/enimaloc?page=2&status=AUTO");
        vi.stubGlobal("fetch", vi.fn(async () => response(200, [])));
        render();

        reopen();
        await flush();

        expect(fetch).toHaveBeenCalledWith("/spa/channel/enimaloc/state?page=2&status=AUTO", expect.anything());
    });

    it.each([
        ["it lists other bindings than the page", PAGE, [...binding("b2", "Other", true)]],
        ["it lists more bindings than the page", PAGE, [...binding("b1", "Old", true), ...binding("b2", "Other", true)]],
        ["it has a DTDD mapping the page has no card for", PAGE,
            [...binding("b1", "Old", true), { name: "DtddMappingChangedEvent", data: { igdbId: "42" } }]],
        ["the page has a DTDD card it has no mapping for", `${PAGE}<mdui-card id="channel-dtdd-mapping-card"></mdui-card>`,
            [...binding("b1", "Old", true)]],
    ])("re-renders the page instead when %s", async (_, page, state) => {
        html(page);
        vi.stubGlobal("fetch", vi.fn(async () => response(200, state)));
        render();

        reopen();
        await flush();

        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc", false, "", { fresh: true });
        expect(document.getElementById("bot").checked).not.toBe(true);
    });

    it.each([
        ["answers an error", () => response(503, "down")],
        ["can't be reached", () => { throw new TypeError("offline"); }],
        ["answers no list", () => response(200, { events: [] })],
    ])("re-renders the page when the state %s", async (_, answer) => {
        html(PAGE);
        vi.stubGlobal("fetch", vi.fn(async () => answer()));
        render();

        reopen();
        await flush();

        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
    });

    it("drops a state that arrives after the visitor moved to another channel", async () => {
        html(PAGE);
        let answer;
        vi.stubGlobal("fetch", vi.fn(() => new Promise(resolve => { answer = resolve; })));
        render();
        reopen();
        history.replaceState(null, "", "/channel/other");
        render();

        answer(response(200, [{ name: "BotStateChangedEvent", data: { state: true } }]));
        await flush();

        expect(document.getElementById("bot").checked).not.toBe(true);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("drops a state answered after that of a later ask, applying the later one alone", async () => {
        html(PAGE);
        const answers = [];
        vi.stubGlobal("fetch", vi.fn(() => new Promise(resolve => answers.push(resolve))));
        render();
        reopen();
        current().emit("open", {});
        expect(answers).toHaveLength(2);

        answers[1](response(200, [...binding("b1", "Newer", true), { name: "BotStateChangedEvent", data: { state: true } }]));
        await flush();
        answers[0](response(200, [...binding("b1", "Older", false), { name: "BotStateChangedEvent", data: { state: false } }]));
        await flush();

        const row = document.querySelector("[data-binding-id=b1]");
        expect(row.querySelector(".binding-game-name").textContent).toBe("Newer");
        expect(row.querySelector(".ccl").checked).toBe(true);
        expect(document.getElementById("bot").checked).toBe(true);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("applies each state answered in the order asked", async () => {
        html(PAGE);
        const answers = [];
        vi.stubGlobal("fetch", vi.fn(() => new Promise(resolve => answers.push(resolve))));
        render();
        reopen();
        current().emit("open", {});

        answers[0](response(200, binding("b1", "First", false)));
        await flush();
        expect(document.querySelector(".binding-game-name").textContent).toBe("First");
        answers[1](response(200, binding("b1", "Second", true)));
        await flush();
        expect(document.querySelector(".binding-game-name").textContent).toBe("Second");
    });

    it("re-renders the OBS dock, which shows the binding of the game played now", async () => {
        window.CatapultDock = { active: () => true, EVENTS: [], apply: vi.fn() };
        vi.stubGlobal("fetch", vi.fn(async () => response(200, [])));
        render();

        reopen();
        await flush();

        expect(fetch).not.toHaveBeenCalled();
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
        delete window.CatapultDock;
    });
});

describe("in the OBS dock", () => {
    let apply;

    beforeEach(() => {
        apply = vi.fn();
        window.CatapultDock = { active: () => true, EVENTS: ["GameChangedEvent", "TwUpdatedEvent"], apply };
        history.replaceState(null, "", "/channel/enimaloc/dock");
    });

    afterEach(() => {
        delete window.CatapultDock;
    });

    it("subscribes to the dock's events as well", () => {
        html(`<section id="obs-dock"></section>`);
        render();

        expect(current().url).toBe("/events/channel/enimaloc");
        expect(current().listeners.TwUpdatedEvent).toHaveLength(1);
    });

    it("hands the dock its events instead of patching the full page it doesn't have", () => {
        html(`<section id="obs-dock"><span id="live" class="hidden" data-if="live" data-on="live:ChannelLiveStateEvent.state"></span></section>`);
        render();

        current().emit("GameChangedEvent", { bindingId: "b7", sourceName: "X" });
        current().emit("BindingDeletedEvent", { bindingId: "b1" });
        current().emit("ChannelLiveStateEvent", { state: true });

        expect(apply).toHaveBeenCalledTimes(1);
        expect(apply).toHaveBeenCalledWith("GameChangedEvent", { bindingId: "b7", sourceName: "X" });
        expect(document.getElementById("live").classList.contains("hidden")).toBe(true);
    });
});

describe("parts built only once shown (lazy.js)", () => {
    beforeEach(async () => {
        await load("visibility", "lazy", "channel-page", "channel-events");
        window.CatapultSpa = { navigate: vi.fn(async () => {}) };
        vi.stubGlobal("fetch", vi.fn(async () => response(200, [])));
    });

    it("subscribes to the events a part not shown yet names", () => {
        html(`<section><template data-lazy><span data-on="steam:SteamConnectionStateEvent.connected"></span></template></section>`);

        render();

        expect(current().listeners.SteamConnectionStateEvent).toHaveLength(1);
    });

    it("catches up on the state when a part shown missed an event applied by hand, not otherwise", async () => {
        html(`<mdui-tab-panel id="settings"><template data-lazy><p></p></template></mdui-tab-panel>
              <mdui-tab-panel id="integrations"><template data-lazy><p></p></template></mdui-tab-panel>`);
        render();

        CatapultLazy.showIn(document.getElementById("settings"));
        await flush();
        expect(fetch).not.toHaveBeenCalled();

        current().emit("ChannelSettingsUpdatedEvent", { settings: null });
        CatapultLazy.showIn(document.getElementById("integrations"));
        await flush();
        expect(fetch).toHaveBeenCalledWith("/spa/channel/enimaloc/state", { headers: { Accept: "application/json" } });
    });
});
