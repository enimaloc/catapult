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

describe("protocol", () => {
    it("exposes the generated actions, events and answers", () => {
        expect(CatapultTwitchat.ACTIONS).toContain("CHAT_FEED_PAUSE");
        expect(CatapultTwitchat.EVENTS).toContain("TRIGGER_LIST");
        expect(CatapultTwitchat.EVENTS).not.toContain("CustomEvent");
        expect(CatapultTwitchat.REPLIES.TRIGGERS_GET_ALL).toEqual(["TRIGGER_LIST"]);
    });
});

describe("actions", () => {
    it("broadcasts an action in Twitchat's envelope, resolving once sent", async () => {
        await expect(CatapultTwitchat.actions.chatFeedPause()).resolves.toBeUndefined();
        await CatapultTwitchat.send("GREET_FEED_READ", { count: 1 });
        expect(broadcasts()).toEqual([
            { origin: "twitchat", id: expect.any(String), type: "CHAT_FEED_PAUSE" },
            { origin: "twitchat", id: expect.any(String), type: "GREET_FEED_READ", data: { count: 1 } },
        ]);
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
            message: "chatFeedPaus isn't a Twitchat action, did you mean chatFeedPause?",
        });
        await expect(CatapultTwitchat.send("SHOUT_OUT")).rejects.toThrow(
            "SHOUT_OUT isn't a Twitchat action, did you mean SHOUTOUT?");
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

describe("relay", () => {
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
