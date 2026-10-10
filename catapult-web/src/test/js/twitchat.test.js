/*
 * twitchat.js against a stubbed CatapultObs: what gets broadcast to Twitchat, the cross-client
 * relay claim, and the trigger list.
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

const sent = (type) => requests.filter(([requestType]) => requestType === type).map(([, data]) => data);

beforeEach(async () => {
    vi.useFakeTimers();
    vi.spyOn(console, "log").mockImplementation(() => {});
    connected = true;
    slot = null;
    requests = [];
    obsListeners = {};
    stubObs();
    await load("twitchat");
});

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
});

async function relay(notification = NOTIFICATION) {
    const result = CatapultTwitchat.relay(notification);
    await vi.advanceTimersByTimeAsync(150);
    return result;
}

describe("relay", () => {
    it("claims the notification, then broadcasts it to Twitchat as a chat message", async () => {
        await expect(relay()).resolves.toBe(true);
        expect(slot).toBe("n-1");
        expect(sent("BroadcastCustomEvent")).toEqual([{
            eventData: {
                origin: "twitchat",
                type: "CUSTOM_CHAT_MESSAGE",
                data: {
                    message: "Catégorie changée", style: "highlight", icon: "info", user: { name: "Catapult" },
                    actions: [{ label: "Annuler", actionType: "URL", url: "https://x", message: null, theme: "primary" }],
                },
            },
        }]);
    });

    it("skips a notification another client claimed", async () => {
        slot = "n-1";
        await expect(relay()).resolves.toBe(false);
        expect(sent("BroadcastCustomEvent")).toEqual([]);
    });

    it("relays anyway when OBS can't coordinate", async () => {
        CatapultObs.requests = new Proxy({}, { get: (_, name) => (data) => {
            if (name === "getPersistentData") return Promise.reject(new Error("boom"));
            requests.push([name[0].toUpperCase() + name.slice(1), data]);
            return Promise.resolve({});
        } });
        await expect(relay()).resolves.toBe(true);
        expect(sent("BroadcastCustomEvent")).toHaveLength(1);
    });

    it("drops the notification while OBS is disconnected", async () => {
        connected = false;
        await expect(relay()).resolves.toBe(false);
        expect(requests).toEqual([]);
    });

    it("leaves user out without an author and actions empty without any", async () => {
        await relay({ id: "n-2", message: "Bot activé", style: null, icon: null, authorName: null, actions: null });
        expect(sent("BroadcastCustomEvent")[0].eventData.data).toEqual(
            { message: "Bot activé", style: null, icon: null, user: undefined, actions: [] });
    });

    it("relays what catapult:twitchat:notify carries", async () => {
        document.dispatchEvent(new CustomEvent("catapult:twitchat:notify", { detail: NOTIFICATION }));
        await vi.advanceTimersByTimeAsync(150);
        expect(sent("BroadcastCustomEvent")).toHaveLength(1);
    });
});

describe("triggers", () => {
    it("asks Twitchat for its triggers on every OBS connection", async () => {
        document.dispatchEvent(new CustomEvent("catapult:obs:connected", { detail: { info: {} } }));
        await vi.advanceTimersByTimeAsync(0);
        expect(sent("BroadcastCustomEvent")).toEqual([
            { eventData: { origin: "twitchat", type: "TRIGGERS_GET_ALL", data: {} } }]);
    });

    it("keeps and announces Twitchat's answer, ignoring other custom events, until OBS disconnects", () => {
        const announced = vi.fn();
        document.addEventListener("catapult:twitchat:triggers", (e) => announced(e.detail));
        const list = [{ id: "t1", name: "!uptime" }];

        obsListeners.CustomEvent({ origin: "other", type: "TRIGGER_LIST", data: { triggers: [{ id: "x" }] } });
        obsListeners.CustomEvent({ origin: "twitchat", type: "TRIGGER_LIST", data: { triggers: list } });
        expect(CatapultTwitchat.triggers()).toEqual(list);
        expect(announced).toHaveBeenCalledExactlyOnceWith({ triggers: list });

        document.dispatchEvent(new CustomEvent("catapult:obs:disconnected", { detail: {} }));
        expect(CatapultTwitchat.triggers()).toEqual([]);
    });
});
