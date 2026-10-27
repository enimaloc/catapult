/*
 * The OBS dock: its channel-wide CCL and TW switches (sent with the blocked lists they leave as
 * they are), the switches of the binding of the game played now, its live events patched in place,
 * and the coalesced re-render left for what it can't patch, which keeps where it was scrolled to.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;

function sw(attrs, checked = false, value = "") {
    return `<mdui-switch ${attrs} data-checked="${checked}" data-test-value="${value}"></mdui-switch>`;
}

/** mdui-switch's checked and value are element properties, which jsdom's unknown element hasn't. */
function hydrate() {
    document.querySelectorAll("mdui-switch").forEach(el => {
        el.checked = el.dataset.checked === "true";
        el.value = el.dataset.testValue;
    });
}

/** The dock as pages/channel-dock.html renders it, spa:* rules included. */
function dock(withBinding = true) {
    html(`
        <section id="obs-dock">
            <span id="channel-current-game" class="${withBinding ? "" : "hidden"}" data-if="hasGame"
                  data-on="hasGame:GameChangedEvent.sourceName"
                  data-value="textContent:GameChangedEvent.sourceName">${withBinding ? "Valheim" : ""}</span>
            <span id="channel-no-game" class="${withBinding ? "hidden" : ""}" data-if="noGame"
                  data-on="noGame:GameChangedEvent.!sourceName">No game</span>
            ${sw(`id="channel-bot-toggle" data-value="checked:BotStateChangedEvent.state"`, true)}
            ${sw(`id="obs-dock-ccl-enabled" data-blocked="ccl-b,ccl-c"`, true)}
            ${sw(`id="obs-dock-tw-enabled" data-blocked=""`)}
            <div class="obs-dock-binding" ${withBinding ? `data-binding-id="b1" data-game-id="508455" data-game-name="Valheim"` : ""}>
                ${withBinding ? `
                    <label class="obs-dock-binding-part">
                        ${sw(`id="obs-dock-binding-ccl-enabled" data-value="checked:CclStateEvent.enabled"`, true)}
                    </label>
                    <div class="obs-dock-binding-part">
                        ${sw(`class="obs-dock-binding-ccl" data-in="checked:BindingUpdatedEvent.ccls"`, true, "ccl-a")}
                        ${sw(`class="obs-dock-binding-ccl" data-in="checked:BindingUpdatedEvent.ccls"`, false, "ccl-d")}
                    </div>
                    <label class="obs-dock-binding-part">
                        ${sw(`id="obs-dock-binding-tw-enabled" data-value="checked:TwEnabledStateEvent.enabled"`)}
                    </label>
                    <div class="obs-dock-binding-part">
                        ${sw(`class="obs-dock-binding-tw" data-in="checked:TwUpdatedEvent.tws,checked:TwResetEvent.tws"`,
                            false, "tw-a")}
                        ${sw(`class="obs-dock-binding-tw" data-in="checked:TwUpdatedEvent.tws,checked:TwResetEvent.tws"`,
                            true, "tw-b")}
                    </div>` : ""}
                <p class="obs-dock-no-binding ${withBinding ? "hidden" : ""}">No binding</p>
                <p class="obs-dock-no-binding ${withBinding ? "hidden" : ""}">No binding</p>
            </div>
        </section>`);
    hydrate();
    render();
}

function flip(el) {
    el.checked = !el.checked;
    el.dispatchEvent(new Event("change"));
}

const byId = id => document.getElementById(id);
const all = selector => document.querySelectorAll(selector);

beforeEach(async () => {
    history.replaceState(null, "", "/channel/enimaloc/dock");
    postJson = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    await load("visibility", "channel-page", "channel-dock");
});

describe("active", () => {
    it("is whether the page is the dock", () => {
        expect(CatapultDock.active()).toBe(false);
        dock();
        expect(CatapultDock.active()).toBe(true);
    });
});

describe("dock mode", () => {
    it("follows the page shown, even one reached by client-side navigation", () => {
        dock();
        expect(document.body.classList.contains("obs-dock-mode")).toBe(true);

        html(`<main>other page</main>`);
        render();
        expect(document.body.classList.contains("obs-dock-mode")).toBe(false);
    });
});

describe("channel-wide switches", () => {
    it("send the CCL feature's state with the blocked CCL unchanged", async () => {
        dock();
        flip(byId("obs-dock-ccl-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/settings/ccl",
            { cclEnabled: false, blockedCcls: ["ccl-b", "ccl-c"] });
    });

    it("send the TW feature's state, with no blocked TW when there are none", async () => {
        dock();
        flip(byId("obs-dock-tw-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/settings/tws", { enabled: true, blockedTws: [] });
    });

    it("are put back when the change doesn't go through", async () => {
        postJson.mockResolvedValue(response(500));
        dock();
        flip(byId("obs-dock-ccl-enabled"));
        await flush();

        expect(byId("obs-dock-ccl-enabled").checked).toBe(true);
    });

    it("still work without a binding for the game played now", async () => {
        dock(false);
        flip(byId("obs-dock-tw-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledTimes(1);
    });
});

describe("the binding of the game played now", () => {
    it("toggles its CCL", async () => {
        dock();
        flip(byId("obs-dock-binding-ccl-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/bindings/b1/ccl-toggle", { enabled: false });
    });

    it("saves its CCL with its game, which catapult-api replaces along with them", async () => {
        dock();
        flip(all(".obs-dock-binding-ccl")[1]);
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/bindings/b1",
            { twitchGameId: "508455", twitchGameName: "Valheim", ccls: ["ccl-a", "ccl-d"] });
    });

    it("toggles and saves its TW through the binding-scoped routes", async () => {
        dock();
        flip(byId("obs-dock-binding-tw-enabled"));
        flip(all(".obs-dock-binding-tw")[1]);
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/bindings/b1/tw-enabled", { enabled: true });
        expect(postJson).toHaveBeenCalledWith("/channel/bindings/b1/tws", { tws: [] });
    });

    it("sends a missing game as null", async () => {
        dock();
        const binding = document.querySelector(".obs-dock-binding");
        delete binding.dataset.gameId;
        delete binding.dataset.gameName;
        flip(all(".obs-dock-binding-ccl")[0]);
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/bindings/b1",
            { twitchGameId: null, twitchGameName: null, ccls: [] });
    });
});

describe("refresh", () => {
    it("re-renders once for a burst of events, keeping the scroll", async () => {
        vi.useFakeTimers();
        dock();
        window.scrollY = 240;
        const scrollTo = vi.spyOn(window, "scrollTo").mockImplementation(() => {});

        CatapultDock.refresh();
        CatapultDock.refresh();
        await vi.advanceTimersByTimeAsync(200);

        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc/dock", false, "", { fresh: true });
        expect(scrollTo).toHaveBeenCalledWith({ top: 240, behavior: "instant" });

        CatapultDock.refresh();
        await vi.advanceTimersByTimeAsync(200);
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(2);
    });
});

describe("live events", () => {
    const checked = selector => Array.from(all(selector)).map(el => el.checked);
    const shown = id => !byId(id).classList.contains("hidden");
    const BLOCKED_CCLS_ONLY_D = { ccl: { enabled: false, blockedCcls: ["ccl-d"] }, tw: { enabled: true, blockedTws: ["tw-a"] } };

    beforeEach(() => {
        vi.useFakeTimers();
        vi.spyOn(window, "scrollTo").mockImplementation(() => {});
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.restoreAllMocks();
    });

    /** Applies an event, then lets any re-render it asked for run. */
    async function apply(eventName, data) {
        CatapultDock.apply(eventName, data);
        await vi.advanceTimersByTimeAsync(1000);
    }

    it.each([
        ["GameChangedEvent", { bindingId: "b1", sourceType: "STEAM", sourceName: "Valheim+" },
            () => expect(byId("channel-current-game").textContent).toBe("Valheim+")],
        ["BotStateChangedEvent", { state: false }, () => expect(byId("channel-bot-toggle").checked).toBe(false)],
        ["CclStateEvent", { bindingId: "b1", enabled: false },
            () => expect(byId("obs-dock-binding-ccl-enabled").checked).toBe(false)],
        ["BindingUpdatedEvent", { bindingId: "b1", twitchGameId: "1", twitchGameName: "Other", ccls: ["ccl-d"] },
            () => {
                expect(checked(".obs-dock-binding-ccl")).toEqual([false, true]);
                expect(document.querySelector(".obs-dock-binding").dataset.gameName).toBe("Other");
            }],
        ["BindingDeletedEvent", { bindingId: "b1" }, () => {
            expect(all(".obs-dock-binding-part")).toHaveLength(0);
            expect(Array.from(all(".obs-dock-no-binding")).some(p => p.classList.contains("hidden"))).toBe(false);
            expect(document.querySelector(".obs-dock-binding").dataset.bindingId).toBeUndefined();
        }],
        ["TwUpdatedEvent", { bindingId: "b1", tws: ["tw-a"] },
            () => expect(checked(".obs-dock-binding-tw")).toEqual([true, false])],
        ["TwResetEvent", { bindingId: "b1" }, () => expect(checked(".obs-dock-binding-tw")).toEqual([false, false])],
        ["TwEnabledStateEvent", { bindingId: "b1", enabled: true },
            () => expect(byId("obs-dock-binding-tw-enabled").checked).toBe(true)],
        ["ChannelSettingsUpdatedEvent", { settings: BLOCKED_CCLS_ONLY_D }, () => {
            expect(byId("obs-dock-ccl-enabled").checked).toBe(false);
            expect(byId("obs-dock-ccl-enabled").dataset.blocked).toBe("ccl-d");
            expect(byId("obs-dock-tw-enabled").checked).toBe(true);
            expect(byId("obs-dock-tw-enabled").dataset.blocked).toBe("tw-a");
            expect(Array.from(all(".obs-dock-binding-ccl")).map(sw => sw.disabled)).toEqual([false, true]);
        }],
    ])("patches %s in place, without re-rendering", async (eventName, data, expectPatched) => {
        dock();
        await apply(eventName, data);

        expectPatched();
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("shows the game going and coming back, when it has no binding either way", async () => {
        dock(false);
        await apply("GameChangedEvent", { bindingId: null, sourceType: "STEAM", sourceName: "Unbound" });

        expect(shown("channel-current-game")).toBe(true);
        expect(shown("channel-no-game")).toBe(false);
        expect(byId("channel-current-game").textContent).toBe("Unbound");

        await apply("GameChangedEvent", { bindingId: null, sourceType: null, sourceName: null });
        expect(shown("channel-current-game")).toBe(false);
        expect(shown("channel-no-game")).toBe(true);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("leaves another binding's events to it", async () => {
        dock();
        await apply("CclStateEvent", { bindingId: "b2", enabled: false });
        await apply("TwUpdatedEvent", { bindingId: "b2", tws: ["tw-a"] });
        await apply("BindingDeletedEvent", { bindingId: "b2" });

        expect(byId("obs-dock-binding-ccl-enabled").checked).toBe(true);
        expect(checked(".obs-dock-binding-tw")).toEqual([false, true]);
        expect(all(".obs-dock-binding-part")).toHaveLength(4);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("keeps a burst of events in place, at the last one's state", async () => {
        dock();
        const observer = new MutationObserver(() => {});
        observer.observe(document.body, { childList: true, subtree: true });
        for (let i = 0; i < 20; i++) {
            const even = i % 2 === 0;
            CatapultDock.apply("CclStateEvent", { bindingId: "b1", enabled: even });
            CatapultDock.apply("TwUpdatedEvent", { bindingId: "b1", tws: even ? ["tw-a"] : ["tw-b"] });
            CatapultDock.apply("BotStateChangedEvent", { state: !even });
            await vi.advanceTimersByTimeAsync(50);
        }

        const added = observer.takeRecords().reduce((n, r) => n + r.addedNodes.length + r.removedNodes.length, 0);
        observer.disconnect();
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
        expect(added).toBe(0);
        expect(byId("obs-dock-binding-ccl-enabled").checked).toBe(false);
        expect(checked(".obs-dock-binding-tw")).toEqual([false, true]);
        expect(byId("channel-bot-toggle").checked).toBe(true);
    });

    it("keeps a switch being flipped as it is, while another one is patched", async () => {
        dock();
        const flipped = all(".obs-dock-binding-ccl")[1];
        flipped.focus();
        flip(flipped);
        await apply("TwEnabledStateEvent", { bindingId: "b1", enabled: true });

        expect(flipped.checked).toBe(true);
        expect(flipped.isConnected).toBe(true);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it.each([
        ["GameChangedEvent", { sourceName: "Valheim" }],
        ["BotStateChangedEvent", {}],
        ["CclStateEvent", { bindingId: "b1" }],
        ["BindingUpdatedEvent", { bindingId: "b1", ccls: ["ccl-a"] }],
        ["BindingDeletedEvent", {}],
        ["TwUpdatedEvent", { bindingId: "b1" }],
        ["TwEnabledStateEvent", { bindingId: "b1" }],
        ["ChannelSettingsUpdatedEvent", { settings: { ccl: { enabled: true, blockedCcls: [] } } }],
        ["ChannelSettingsUpdatedEvent", { settings: { ccl: { enabled: true }, tw: { enabled: true, blockedTws: [] } } }],
        ["ChannelSettingsUpdatedEvent", { settings: { ccl: { enabled: true, blockedCcls: [] }, tw: { enabled: true } } }],
        ["TwResetEvent", null],
    ])("re-renders for a %s lacking a field it patches with", async (eventName, data) => {
        dock();
        await apply(eventName, data);

        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
        expect(byId("obs-dock-binding-ccl-enabled").checked).toBe(true);
    });

    it("re-renders when the game played now goes to a binding it hasn't", async () => {
        dock();
        await apply("GameChangedEvent", { bindingId: "b2", sourceType: "STEAM", sourceName: "Other" });
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);

        dock(false);
        await apply("GameChangedEvent", { bindingId: "b3", sourceType: "STEAM", sourceName: "Bound" });
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(2);
    });

    it("re-renders for a binding coming for the game it shows unbound", async () => {
        dock(false);
        await apply("GameChangedEvent", { bindingId: null, sourceType: "STEAM", sourceName: "Unbound" });
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();

        await apply("BindingUpdatedEvent", { bindingId: "b9", twitchGameId: "1", twitchGameName: "Unbound", ccls: [] });
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
    });

    it("leaves a binding coming while no game is played", async () => {
        dock(false);
        await apply("BindingUpdatedEvent", { bindingId: "b9", twitchGameId: "1", twitchGameName: "Other", ccls: [] });

        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("leaves another binding's update alone while it shows one", async () => {
        dock();
        const observer = new MutationObserver(() => {});
        observer.observe(document.body, { childList: true, subtree: true, attributes: true });
        await apply("BindingUpdatedEvent", { bindingId: "b2", twitchGameId: "1", twitchGameName: "Other", ccls: ["ccl-d"] });

        const records = observer.takeRecords();
        observer.disconnect();
        expect(records).toHaveLength(0);
        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });

    it("re-renders once more for an event arriving while a re-render is under way", async () => {
        dock();
        let finish;
        CatapultSpa.navigate.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
        CatapultDock.apply("GameChangedEvent", { bindingId: "b2", sourceType: "STEAM", sourceName: "Other" });
        await vi.advanceTimersByTimeAsync(200);
        CatapultDock.apply("BotStateChangedEvent", { state: false });
        finish();
        await vi.advanceTimersByTimeAsync(200);

        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(2);
        expect(byId("channel-bot-toggle").checked).toBe(true);
    });

    it("ignores the events that aren't its own, and any outside the dock", async () => {
        dock();
        await apply("ChannelLiveStateEvent", { state: true });
        html(`<main>other page</main>`);
        await apply("BotStateChangedEvent", {});

        expect(CatapultSpa.navigate).not.toHaveBeenCalled();
    });
});

it("does nothing outside the dock", async () => {
    html(`${sw(`id="obs-dock-ccl-enabled"`)}`);
    hydrate();
    render();
    flip(byId("obs-dock-ccl-enabled"));
    await flush();

    expect(postJson).not.toHaveBeenCalled();
});
