/*
 * twitchat.js against a stubbed CatapultObs and the generated twitchat-protocol.js: what gets
 * broadcast to Twitchat, how its answers and events come back, and the notification relay.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load } from "./helpers.js";

const NOTIFICATION = {
    id: "n-1", message: "Catégorie changée", style: "highlight", icon: "info", authorName: "Catapult",
    actions: [{ label: "Annuler", actionType: "URL", url: "https://x", message: null, theme: "primary", extra: 1 }],
};

let connected;
let slot;
let requests;
let obsListeners;

function stubObs() {
    const answer = (requestType, requestData) => {
        requests.push([requestType, requestData]);
        if (requestType === "GetPersistentData") return Promise.resolve({ slotValue: slot });
        if (requestType === "SetPersistentData") slot = requestData.slotValue;
        return Promise.resolve({});
    };
    window.CatapultObs = {
        isConnected: () => connected,
        on: (eventType, handler) => { obsListeners[eventType] = handler; },
        requests: new Proxy({}, { get: (_, name) => (data) => answer(name[0].toUpperCase() + name.slice(1), data) }),
    };
}

/** What this page broadcast to Twitchat. */
const broadcasts = () => requests.filter(([type]) => type === "BroadcastCustomEvent").map(([, data]) => data.eventData);

/** A message from Twitchat (or another client), as OBS hands it over. */
function twitchatSends(type, data, id = crypto.randomUUID()) {
    obsListeners.CustomEvent({ origin: "twitchat", id, type, data });
}

beforeEach(async () => {
    vi.useFakeTimers();
    vi.spyOn(console, "log").mockImplementation(() => {});
    connected = true;
    slot = null;
    requests = [];
    obsListeners = {};
    stubObs();
    await load("suggest", "twitchat-protocol", "twitchat");
});

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
});

/** Twitchat answering this page's probe as one speaking `protocol` would. */
async function twitchatAnswersProbe(protocol) {
    await vi.advanceTimersByTimeAsync(0);
    twitchatSends(protocol === "beta" ? "ON_GLOBAL_STATES" : "SET_COLS_COUNT", {});
}

describe("protocols", () => {
    it("exposes both generated protocols, the stable one until detected", () => {
        const { stable, beta } = CatapultTwitchat.PROTOCOLS;
        expect(stable.actions).toContain("CHAT_FEED_PAUSE");
        expect(stable.events).not.toContain("CustomEvent");
        expect(stable.replies.TRIGGERS_GET_ALL).toEqual(["TRIGGER_LIST"]);
        expect(beta.actions).toContain("SET_CHAT_FEED_PAUSE_STATE");
        expect(beta.events).toContain("ON_TRIGGER_LIST");
        expect(beta.replies.GET_TRIGGER_LIST).toEqual(["ON_TRIGGER_LIST"]);

        expect(CatapultTwitchat.protocol).toBeNull();
        expect(CatapultTwitchat.ACTIONS).toBe(stable.actions);
        expect(CatapultTwitchat.EVENTS).toBe(stable.events);
        expect(CatapultTwitchat.REPLIES).toBe(stable.replies);
    });

    it("detects the protocol from an event only one of them has", () => {
        twitchatSends("ON_TWITCHAT_READY");
        expect(CatapultTwitchat.protocol).toBe("beta");
        expect(CatapultTwitchat.ACTIONS).toBe(CatapultTwitchat.PROTOCOLS.beta.actions);
        expect(Object.keys(CatapultTwitchat.actions)).toContain("setChatFeedPauseState");
        expect(Object.keys(CatapultTwitchat.actions)).not.toContain("chatFeedPause");

        twitchatSends("TWITCHAT_READY");
        expect(CatapultTwitchat.protocol).toBe("stable");
    });

    it("doesn't detect it from the actions other clients send", () => {
        twitchatSends("GET_COLS_COUNT");
        twitchatSends("SET_CHAT_FEED_PAUSE_STATE", { colIndex: 0 });
        expect(CatapultTwitchat.protocol).toBeNull();
    });

    it("probes Twitchat before the first action, then sends it in the detected protocol", async () => {
        const triggers = CatapultTwitchat.send("GET_TRIGGER_LIST");
        await twitchatAnswersProbe("beta");
        await vi.advanceTimersByTimeAsync(0);
        twitchatSends("ON_TRIGGER_LIST", { triggerList: [] });

        await expect(triggers).resolves.toEqual({ triggerList: [] });
        expect(broadcasts().map(({ type }) => type)).toEqual(["GET_COLS_COUNT", "GET_GLOBAL_STATES", "GET_TRIGGER_LIST"]);
        await CatapultTwitchat.actions.setChatFeedPauseState({ colIndex: 0 });
        expect(broadcasts()).toHaveLength(4);
    });

    it("rejects an action of the other protocol, suggesting one of this Twitchat's", async () => {
        twitchatSends("ON_TWITCHAT_READY");
        await expect(CatapultTwitchat.actions.chatFeedPause()).rejects.toMatchObject({
            code: "UNKNOWN_ACTION", action: "CHAT_FEED_PAUSE",
            message: expect.stringMatching(/^chatFeedPause isn't a beta Twitchat action/),
        });
        await expect(CatapultTwitchat.actions.setChatFeedPauseStat()).rejects.toMatchObject({
            code: "UNKNOWN_ACTION", suggestion: "SET_CHAT_FEED_PAUSE_STATE",
            message: "setChatFeedPauseStat isn't a beta Twitchat action, did you mean setChatFeedPauseState?",
        });
        expect(broadcasts()).toEqual([]);
    });

    it("detects it anew when OBS reconnects", async () => {
        twitchatSends("ON_TWITCHAT_READY");
        document.dispatchEvent(new CustomEvent("catapult:obs:connected"));
        expect(CatapultTwitchat.protocol).toBeNull();
        await twitchatAnswersProbe("stable");
        expect(CatapultTwitchat.protocol).toBe("stable");
        expect(broadcasts().map(({ type }) => type)).toEqual(["GET_COLS_COUNT", "GET_GLOBAL_STATES"]);
    });

    it("sends in the stable protocol when Twitchat doesn't answer the probes", async () => {
        const sent = CatapultTwitchat.actions.chatFeedPause(undefined, { timeout: 500 });
        await vi.advanceTimersByTimeAsync(500);
        await expect(sent).resolves.toBeUndefined();
        expect(CatapultTwitchat.protocol).toBeNull();
        expect(broadcasts().map(({ type }) => type)).toEqual(["GET_COLS_COUNT", "GET_GLOBAL_STATES", "CHAT_FEED_PAUSE"]);
    });

    it("configure() forces the picked branch, or detects it anew on auto", async () => {
        CatapultTwitchat.configure("beta");
        expect(CatapultTwitchat.protocol).toBe("beta");
        twitchatSends("TWITCHAT_READY");
        expect(CatapultTwitchat.protocol).toBe("beta");

        CatapultTwitchat.configure("auto");
        expect(CatapultTwitchat.protocol).toBeNull();
        await twitchatAnswersProbe("stable");
        expect(CatapultTwitchat.protocol).toBe("stable");

        CatapultTwitchat.configure("auto");
        expect(CatapultTwitchat.protocol).toBe("stable");
        expect(() => CatapultTwitchat.configure("main")).toThrow("Unknown Twitchat protocol main");
    });

    it("keeps a forced protocol whatever Twitchat sends", async () => {
        CatapultTwitchat.useProtocol("beta");
        twitchatSends("TWITCHAT_READY");
        document.dispatchEvent(new CustomEvent("catapult:obs:connected"));
        expect(CatapultTwitchat.protocol).toBe("beta");
        expect(() => CatapultTwitchat.useProtocol("nightly")).toThrow("Unknown Twitchat protocol nightly");
        await CatapultTwitchat.actions.setGreetFeedReadAll();
        // Pinged all the same on reconnecting, to know whether a Twitchat is there.
        expect(broadcasts().map(({ type }) => type)).toEqual(["GET_COLS_COUNT", "GET_GLOBAL_STATES", "SET_GREET_FEED_READ_ALL"]);
    });
});

describe("presence", () => {
    let announced;

    beforeEach(() => {
        announced = [];
        document.addEventListener("catapult:twitchat:presence", (event) => announced.push(event.detail.connected));
    });

    it("is connected once Twitchat sends an event, not when another client sends an action", () => {
        twitchatSends("CHAT_FEED_PAUSE");
        expect(CatapultTwitchat.isTwitchatConnected()).toBe(false);

        twitchatSends("ON_TWITCHAT_READY");
        twitchatSends("FOLLOW", {});
        expect(CatapultTwitchat.isTwitchatConnected()).toBe(true);
        expect(announced).toEqual([true]);
    });

    it("pings Twitchat when OBS connects", async () => {
        document.dispatchEvent(new CustomEvent("catapult:obs:connected"));
        await twitchatAnswersProbe("beta");
        await vi.advanceTimersByTimeAsync(0);
        expect(CatapultTwitchat.isTwitchatConnected()).toBe(true);
        expect(CatapultTwitchat.protocol).toBe("beta");
    });

    it("is disconnected when OBS connects without a Twitchat answering", async () => {
        twitchatSends("TWITCHAT_READY");
        document.dispatchEvent(new CustomEvent("catapult:obs:connected"));
        await vi.advanceTimersByTimeAsync(3000);
        expect(CatapultTwitchat.isTwitchatConnected()).toBe(false);
        expect(announced).toEqual([true, false]);
    });

    it("pings a quiet Twitchat, and is disconnected once it stops answering", async () => {
        twitchatSends("TWITCHAT_READY");
        await vi.advanceTimersByTimeAsync(20000);
        expect(broadcasts()).toEqual([]);

        await vi.advanceTimersByTimeAsync(20000);
        expect(broadcasts().map(({ type }) => type)).toEqual(["GET_COLS_COUNT", "GET_GLOBAL_STATES"]);
        twitchatSends("SET_COLS_COUNT", {});
        await vi.advanceTimersByTimeAsync(0);
        expect(CatapultTwitchat.isTwitchatConnected()).toBe(true);

        await vi.advanceTimersByTimeAsync(40000 + 3000);
        expect(CatapultTwitchat.isTwitchatConnected()).toBe(false);
    });

    it("is disconnected with OBS", () => {
        twitchatSends("TWITCHAT_READY");
        document.dispatchEvent(new CustomEvent("catapult:obs:disconnected"));
        expect(CatapultTwitchat.isTwitchatConnected()).toBe(false);
    });
});

describe("actions", () => {
    beforeEach(() => CatapultTwitchat.useProtocol("stable"));

    it("broadcasts an action in Twitchat's envelope, resolving once sent", async () => {
        await expect(CatapultTwitchat.actions.chatFeedPause()).resolves.toBeUndefined();
        await CatapultTwitchat.send("GREET_FEED_READ", { count: 1 });
        expect(broadcasts()).toEqual([
            { origin: "twitchat", id: expect.any(String), type: "CHAT_FEED_PAUSE", data: {} },
            { origin: "twitchat", id: expect.any(String), type: "GREET_FEED_READ", data: { count: 1 } },
        ]);
    });

    it("works without crypto.randomUUID, as on a page served over plain HTTP", async () => {
        const realCrypto = globalThis.crypto;
        vi.stubGlobal("crypto", { getRandomValues: (array) => realCrypto.getRandomValues(array) });
        await CatapultTwitchat.actions.chatFeedPause();
        expect(broadcasts()[0].id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    });

    it("resolves a get action with the data of Twitchat's answer", async () => {
        const triggers = CatapultTwitchat.actions.triggersGetAll();
        await vi.advanceTimersByTimeAsync(0);
        twitchatSends("TRIGGER_LIST", { triggers: [{ id: "t1", name: "!uptime" }] });
        await expect(triggers).resolves.toEqual({ triggers: [{ id: "t1", name: "!uptime" }] });
    });

    it("takes the first of several possible answers", async () => {
        const timers = CatapultTwitchat.send("GET_CURRENT_TIMERS");
        twitchatSends("COUNTDOWN_START", { id: "c1" });
        twitchatSends("TIMER_START", { id: "t1" });
        await expect(timers).resolves.toEqual({ id: "c1" });
    });

    it("rejects a get action Twitchat doesn't answer in time", async () => {
        const presence = CatapultTwitchat.actions.getWheelOverlayPresence(undefined, { timeout: 500 });
        const outcome = presence.catch((e) => e);
        await vi.advanceTimersByTimeAsync(500);
        expect(await outcome).toMatchObject({ name: "TwitchatError", code: "TIMEOUT", action: "GET_WHEEL_OVERLAY_PRESENCE" });
    });

    it("rejects an unknown action without sending it, suggesting the closest one", async () => {
        await expect(CatapultTwitchat.actions.chatFeedPaus()).rejects.toMatchObject({
            code: "UNKNOWN_ACTION", action: "CHAT_FEED_PAUS", suggestion: "CHAT_FEED_PAUSE",
            message: "chatFeedPaus isn't a stable Twitchat action, did you mean chatFeedPause?",
        });
        await expect(CatapultTwitchat.send("SHOUT_OUT")).rejects.toThrow(
            "SHOUT_OUT isn't a stable Twitchat action, did you mean SHOUTOUT?");
        expect(broadcasts()).toEqual([]);
    });

    it("rejects while OBS is disconnected", async () => {
        connected = false;
        await expect(CatapultTwitchat.actions.stopTts()).rejects.toMatchObject({ code: "NOT_CONNECTED" });
        expect(broadcasts()).toEqual([]);
    });

    it("lists every action as a real property, and isn't a thenable", async () => {
        expect(Object.keys(CatapultTwitchat.actions)).toHaveLength(CatapultTwitchat.ACTIONS.length);
        expect(Object.keys(CatapultTwitchat.actions)).toContain("triggersGetAll");
        expect(CatapultTwitchat.actions.then).toBeUndefined();
        await expect(Promise.resolve(CatapultTwitchat.actions)).resolves.toBe(CatapultTwitchat.actions);
    });
});

describe("events", () => {
    beforeEach(() => CatapultTwitchat.useProtocol("stable"));

    it("dispatches Twitchat's messages by type and to *, until unsubscribed", () => {
        const follow = vi.fn();
        const all = vi.fn();
        const unsubscribe = CatapultTwitchat.on("FOLLOW", follow);
        CatapultTwitchat.on("*", all);

        twitchatSends("FOLLOW", { user: { login: "a" } }, "e1");
        twitchatSends("MENTION", { username: "b" });
        unsubscribe();
        twitchatSends("FOLLOW", { user: { login: "c" } });

        expect(follow.mock.calls).toEqual([
            [{ user: { login: "a" } }, { origin: "twitchat", id: "e1", type: "FOLLOW", data: { user: { login: "a" } } }]]);
        expect(all.mock.calls.map(([, envelope]) => envelope.type)).toEqual(["FOLLOW", "MENTION", "FOLLOW"]);
    });

    it("ignores other origins and this page's own echoes", async () => {
        const all = vi.fn();
        CatapultTwitchat.on("*", all);
        obsListeners.CustomEvent({ origin: "other", type: "FOLLOW", data: {} });
        await CatapultTwitchat.actions.chatFeedPause();
        twitchatSends("CHAT_FEED_PAUSE", undefined, broadcasts()[0].id);
        expect(all).not.toHaveBeenCalled();

        twitchatSends("CHAT_FEED_PAUSE");
        expect(all).toHaveBeenCalledOnce();
    });

    it("once() resolves with the next one only", async () => {
        const next = CatapultTwitchat.once("TWITCHAT_READY");
        twitchatSends("TWITCHAT_READY", { ready: 1 });
        twitchatSends("TWITCHAT_READY", { ready: 2 });
        await expect(next).resolves.toEqual({ ready: 1 });
    });
});

describe("debug", () => {
    beforeEach(() => CatapultTwitchat.useProtocol("stable"));

    afterEach(() => {
        localStorage.clear();
    });

    it("logs every message with its direction while on, but not this page's echoes", async () => {
        CatapultTwitchat.debug = true;
        await CatapultTwitchat.actions.chatFeedPause();
        twitchatSends("CHAT_FEED_PAUSE", undefined, broadcasts()[0].id);
        twitchatSends("FOLLOW", { user: "a" }, "e1");

        const lines = console.log.mock.calls.filter(([line]) => /[↑↓]/.test(line));
        expect(lines).toEqual([
            ["[twitchat] ↑ CHAT_FEED_PAUSE", { origin: "twitchat", id: broadcasts()[0].id, type: "CHAT_FEED_PAUSE", data: {} }],
            ["[twitchat] ↓ FOLLOW", { origin: "twitchat", id: "e1", type: "FOLLOW", data: { user: "a" } }],
        ]);

        CatapultTwitchat.debug = false;
        twitchatSends("FOLLOW", {});
        expect(console.log.mock.calls.filter(([line]) => /[↑↓]/.test(line))).toHaveLength(2);
    });

    it("is remembered across reloads", async () => {
        CatapultTwitchat.debug = true;
        await load("suggest", "twitchat-protocol", "twitchat");
        expect(CatapultTwitchat.debug).toBe(true);
        CatapultTwitchat.debug = false;
        await load("suggest", "twitchat-protocol", "twitchat");
        expect(CatapultTwitchat.debug).toBe(false);
    });
});

describe("relay", () => {
    beforeEach(() => CatapultTwitchat.useProtocol("stable"));

    async function relay(notification = NOTIFICATION) {
        const result = CatapultTwitchat.relay(notification);
        await vi.advanceTimersByTimeAsync(150);
        return result;
    }

    it("claims the notification, then posts it as a custom chat message", async () => {
        await expect(relay()).resolves.toBe(true);
        expect(slot).toBe("n-1");
        expect(broadcasts()).toEqual([{
            origin: "twitchat",
            id: expect.any(String),
            type: "CUSTOM_CHAT_MESSAGE",
            data: {
                message: "Catégorie changée", style: "highlight", icon: "info", user: { name: "Catapult" },
                actions: [{ label: "Annuler", actionType: "URL", url: "https://x", message: null, theme: "primary" }],
            },
        }]);
    });

    it("skips a notification another client claimed", async () => {
        slot = "n-1";
        await expect(relay()).resolves.toBe(false);
        expect(broadcasts()).toEqual([]);
    });

    it("relays anyway when OBS can't coordinate", async () => {
        const requestsStub = CatapultObs.requests;
        CatapultObs.requests = new Proxy({}, { get: (_, name) => (name === "getPersistentData"
            ? () => Promise.reject(new Error("boom")) : requestsStub[name]) });
        await expect(relay()).resolves.toBe(true);
        expect(broadcasts()).toHaveLength(1);
    });

    it("drops the notification while OBS is disconnected", async () => {
        connected = false;
        await expect(relay()).resolves.toBe(false);
        expect(requests).toEqual([]);
    });

    it("leaves user out without an author and actions empty without any", async () => {
        await relay({ id: "n-2", message: "Bot activé", style: null, icon: null, authorName: null, actions: null });
        expect(broadcasts()[0].data).toEqual({ message: "Bot activé", style: null, icon: null, user: undefined, actions: [] });
    });

    it("relays what catapult:twitchat:notify carries", async () => {
        document.dispatchEvent(new CustomEvent("catapult:twitchat:notify", { detail: NOTIFICATION }));
        await vi.advanceTimersByTimeAsync(150);
        expect(broadcasts()).toHaveLength(1);
    });
});
