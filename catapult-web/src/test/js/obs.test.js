/*
 * obs.js against a fake WebSocket: each test drives the server side by hand (Hello, Identified,
 * responses, events, close) and checks what CatapultObs sends and resolves.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, load } from "./helpers.js";

let sockets;

class FakeSocket {
    constructor(url, protocol) {
        this.url = url;
        this.protocol = protocol;
        this.sent = [];
        this.closedWith = null;
        sockets.push(this);
    }
    send(data) { this.sent.push(JSON.parse(data)); }
    close(code) { this.closedWith = code; }
    serverSends(op, d) { this.onmessage?.({ data: JSON.stringify({ op, d }) }); }
    serverCloses(code, reason = "") { this.onclose?.({ code, reason }); }
    /** The d of the last frame sent with this op. */
    lastSent(op) { return this.sent.filter((frame) => frame.op === op).at(-1)?.d; }
}

const last = () => sockets.at(-1);

/** Answers the last request sent on socket. */
function answer(socket, op, d) {
    const requestId = socket.sent.filter((frame) => frame.op === op - 1).at(-1).d.requestId;
    socket.serverSends(op, { requestId, ...d });
}

/** OBS's answer to the GetVersion sent once identified: no list means no request type check. */
function answerVersion(socket, availableRequests) {
    answer(socket, 7, { requestType: "GetVersion", requestStatus: { result: true, code: 100 },
        responseData: availableRequests ? { availableRequests } : {} });
}

async function connected(options = {}, availableRequests) {
    const promise = CatapultObs.connect({ host: "localhost", port: 4455, ...options });
    last().serverSends(0, { obsWebSocketVersion: "5.5.0", rpcVersion: 1 });
    await flush();
    last().serverSends(2, { negotiatedRpcVersion: 1 });
    answerVersion(last(), availableRequests);
    await promise;
    return last();
}

beforeEach(async () => {
    sockets = [];
    vi.stubGlobal("WebSocket", FakeSocket);
    await load("suggest", "obs");
});

describe("EventSubscription", () => {
    it("puts every low-volume category in All, high-volume ones aside", () => {
        const { All, General, Ui, Canvases, InputVolumeMeters } = CatapultObs.EventSubscription;
        expect(Canvases).toBe(1 << 11);
        expect(All).toBe((1 << 12) - 1);
        expect(All & (General | Ui | Canvases)).toBe(General | Ui | Canvases);
        expect(All & InputVolumeMeters).toBe(0);
    });
});

describe("connect", () => {
    it("identifies over the json subprotocol and resolves with what OBS announced", async () => {
        const promise = CatapultObs.connect({ host: "localhost", port: 4455 });
        const socket = last();
        socket.serverSends(0, { obsWebSocketVersion: "5.5.0", rpcVersion: 1 });
        await flush();
        socket.serverSends(2, { negotiatedRpcVersion: 1 });
        answerVersion(socket, ["GetVersion", "GetSceneList"]);

        await expect(promise).resolves.toEqual({
            obsWebSocketVersion: "5.5.0", rpcVersion: 1, authenticated: false, negotiatedRpcVersion: 1,
            availableRequests: ["GetVersion", "GetSceneList"],
        });
        expect(socket.url).toBe("ws://localhost:4455");
        expect(socket.protocol).toBe("obswebsocket.json");
        expect(socket.sent).toEqual([
            { op: 1, d: { rpcVersion: 1 } },
            { op: 6, d: { requestType: "GetVersion", requestId: expect.any(String) } },
        ]);
        expect(CatapultObs.isConnected()).toBe(true);
        expect(CatapultObs.info().negotiatedRpcVersion).toBe(1);
    });

    it("uses wss when secure and passes the event subscriptions", async () => {
        const { All, InputVolumeMeters } = CatapultObs.EventSubscription;
        const socket = await connected({ secure: true, eventSubscriptions: All | InputVolumeMeters });
        expect(socket.url).toBe("wss://localhost:4455");
        expect(socket.lastSent(1)).toEqual({ rpcVersion: 1, eventSubscriptions: All | InputVolumeMeters });
    });

    it("answers the auth challenge with base64(sha256(base64(sha256(password + salt)) + challenge))", async () => {
        const promise = CatapultObs.connect({ host: "h", port: 1, password: "secret" });
        last().serverSends(0, { rpcVersion: 1, authentication: { salt: "s", challenge: "c" } });
        await vi.waitFor(() => expect(last().sent).toHaveLength(1));
        // Reference value computed with the same algorithm through node:crypto.
        const { createHash } = await import("node:crypto");
        const b64 = (input) => createHash("sha256").update(input).digest("base64");
        expect(last().lastSent(1).authentication).toBe(b64(b64("secrets") + "c"));
        last().serverSends(2, { negotiatedRpcVersion: 1 });
        answerVersion(last());
        await expect(promise).resolves.toMatchObject({ authenticated: true });
    });

    it("computes the same auth response without crypto.subtle, as on a page served over plain HTTP", async () => {
        const { createHash } = await import("node:crypto");
        const b64 = (input) => createHash("sha256").update(input).digest("base64");
        const realCrypto = globalThis.crypto;
        vi.stubGlobal("crypto", { getRandomValues: (array) => realCrypto.getRandomValues(array) });
        // Lengths around SHA-256's 55/56/64-byte padding boundaries, and multi-byte UTF-8.
        for (const password of ["", "secret", "x".repeat(47), "x".repeat(48), "x".repeat(56), "é".repeat(100)]) {
            const promise = CatapultObs.connect({ host: "h", port: 1, password: password || "p" });
            last().serverSends(0, { rpcVersion: 1, authentication: { salt: "salt-0123456789", challenge: "c" } });
            await vi.waitFor(() => expect(last().sent).toHaveLength(1));
            expect(last().lastSent(1).authentication).toBe(b64(b64(`${password || "p"}salt-0123456789`) + "c"));
            last().serverSends(2, { negotiatedRpcVersion: 1 });
            answerVersion(last());
            await promise;
        }
        vi.unstubAllGlobals();
        vi.stubGlobal("WebSocket", FakeSocket);
    });

    it("rejects with the close code when OBS refuses the Identify, as on a wrong password", async () => {
        const promise = CatapultObs.connect({ host: "h", port: 1, password: "bad" });
        last().serverCloses(CatapultObs.CloseCode.AuthenticationFailed, "Authentication failed.");
        const error = await promise.catch((e) => e);
        expect(error).toBeInstanceOf(CatapultObs.ObsError);
        expect(error.code).toBe(4009);
        expect(error.message).toBe("Connection closed (AuthenticationFailed): Authentication failed.");
        expect(CatapultObs.isConnected()).toBe(false);
    });
});

describe("call", () => {
    it("resolves with responseData, or {} when OBS sends none", async () => {
        const socket = await connected();
        const list = CatapultObs.call("GetSceneList");
        expect(socket.lastSent(6)).toEqual({ requestType: "GetSceneList", requestId: expect.any(String) });
        answer(socket, 7, { requestType: "GetSceneList", requestStatus: { result: true, code: 100 }, responseData: { scenes: [] } });
        await expect(list).resolves.toEqual({ scenes: [] });

        const set = CatapultObs.call("SetCurrentProgramScene", { sceneName: "A" });
        expect(socket.lastSent(6).requestData).toEqual({ sceneName: "A" });
        answer(socket, 7, { requestType: "SetCurrentProgramScene", requestStatus: { result: true, code: 100 } });
        await expect(set).resolves.toEqual({});
    });

    it("rejects with an ObsError carrying the status code and comment", async () => {
        const socket = await connected();
        const request = CatapultObs.call("GetInputSettings", { inputName: "x" });
        answer(socket, 7, { requestType: "GetInputSettings", requestStatus: { result: false, code: 600, comment: "No source was found" } });
        await expect(request).rejects.toMatchObject({
            name: "ObsError", code: 600, requestType: "GetInputSettings", comment: "No source was found",
            message: "GetInputSettings failed (600): No source was found",
        });
    });

    it("rejects right away when not connected", async () => {
        await expect(CatapultObs.call("GetSceneList")).rejects.toThrow("Not connected");
    });
});

describe("requests", () => {
    it("turns each method into the request of the same name", async () => {
        const socket = await connected();
        const scenes = CatapultObs.requests.getSceneList();
        expect(socket.lastSent(6)).toEqual({ requestType: "GetSceneList", requestId: expect.any(String) });
        answer(socket, 7, { requestType: "GetSceneList", requestStatus: { result: true, code: 100 }, responseData: { scenes: [] } });
        await expect(scenes).resolves.toEqual({ scenes: [] });

        CatapultObs.requests.SetCurrentProgramScene({ sceneName: "A" });
        expect(socket.lastSent(6)).toMatchObject({ requestType: "SetCurrentProgramScene", requestData: { sceneName: "A" } });
    });

    it("reuses the same function per name", () => {
        expect(CatapultObs.requests.getVersion).toBe(CatapultObs.requests.getVersion);
    });

    it("isn't a thenable and has no shortcut for non-request names", async () => {
        await connected();
        expect(CatapultObs.requests.then).toBeUndefined();
        expect(CatapultObs.requests.toJSON).toBeUndefined();
        expect(CatapultObs.requests[Symbol.iterator]).toBeUndefined();
        await expect(Promise.resolve(CatapultObs.requests)).resolves.toBe(CatapultObs.requests);
        expect(last().lastSent(6).requestType).toBe("GetVersion");
    });
});

describe("request type check", () => {
    const AVAILABLE = ["GetVersion", "GetSceneList", "SetStudioModeEnabled", "GetStudioModeEnabled"];

    it("rejects an unknown shortcut without sending it, suggesting the closest one", async () => {
        const socket = await connected({}, AVAILABLE);
        const sent = socket.sent.length;
        const error = await CatapultObs.requests.setStudioModeEnable({ studioModeEnabled: true }).catch((e) => e);
        expect(error).toMatchObject({
            name: "ObsError", code: 204, requestType: "SetStudioModeEnable", suggestion: "SetStudioModeEnabled",
            message: "setStudioModeEnable isn't a request this OBS knows, did you mean setStudioModeEnabled?",
        });
        expect(socket.sent).toHaveLength(sent);
    });

    it("writes call()'s errors with request type names, and suggests nothing far off", async () => {
        await connected({}, AVAILABLE);
        await expect(CatapultObs.call("GetScenList")).rejects.toThrow(
            "GetScenList isn't a request this OBS knows, did you mean GetSceneList?");
        await expect(CatapultObs.call("ExplodeEverything")).rejects.toMatchObject({
            message: "ExplodeEverything isn't a request this OBS knows", suggestion: null });
    });

    it("rejects a batch holding an unknown request type without sending any of it", async () => {
        const socket = await connected({}, AVAILABLE);
        await expect(CatapultObs.callBatch([{ requestType: "GetVersion" }, { requestType: "GetSceneLis" }]))
            .rejects.toMatchObject({ suggestion: "GetSceneList" });
        expect(socket.lastSent(8)).toBeUndefined();
    });

    it("lists the known shortcuts as real properties, for the console's autocompletion", async () => {
        await connected({}, AVAILABLE);
        CatapultObs.requests.setStudioModeEnable().catch(() => {});
        expect(Object.keys(CatapultObs.requests)).toEqual(
            ["getVersion", "getSceneList", "setStudioModeEnabled", "getStudioModeEnabled"]);
        expect(CatapultObs.requests.getSceneList).toBe(Object.getOwnPropertyDescriptor(CatapultObs.requests, "getSceneList").value);

        await connected({}, ["GetVersion"]);
        expect(Object.keys(CatapultObs.requests)).toEqual(["getVersion"]);
    });

    it("lets OBS judge when the list is unknown", async () => {
        const socket = await connected();
        CatapultObs.requests.setStudioModeEnable({ studioModeEnabled: true });
        expect(socket.lastSent(6).requestType).toBe("SetStudioModeEnable");
    });

    it("still connects when GetVersion fails", async () => {
        const promise = CatapultObs.connect({ host: "h", port: 1 });
        last().serverSends(0, { rpcVersion: 1 });
        await flush();
        last().serverSends(2, { negotiatedRpcVersion: 1 });
        answer(last(), 7, { requestType: "GetVersion", requestStatus: { result: false, code: 500 } });
        await expect(promise).resolves.toMatchObject({ negotiatedRpcVersion: 1 });
        expect(CatapultObs.info().availableRequests).toBeUndefined();
    });
});

describe("callBatch", () => {
    it("sends the options and maps each result without rejecting on a failed one", async () => {
        const socket = await connected();
        const { SerialFrame } = CatapultObs.RequestBatchExecutionType;
        const batch = CatapultObs.callBatch(
            [{ requestType: "GetVersion" }, { requestType: "GetInputSettings", requestData: { inputName: "x" } }],
            { haltOnFailure: false, executionType: SerialFrame });
        expect(socket.lastSent(8)).toEqual({
            requestId: expect.any(String), haltOnFailure: false, executionType: SerialFrame,
            requests: [{ requestType: "GetVersion" }, { requestType: "GetInputSettings", requestData: { inputName: "x" } }],
        });
        answer(socket, 9, {
            results: [
                { requestType: "GetVersion", requestStatus: { result: true, code: 100 }, responseData: { rpcVersion: 1 } },
                { requestType: "GetInputSettings", requestStatus: { result: false, code: 600 } },
            ],
        });
        const [ok, ko] = await batch;
        expect(ok).toEqual({ requestType: "GetVersion", ok: true, data: { rpcVersion: 1 } });
        expect(ko).toMatchObject({ requestType: "GetInputSettings", ok: false, error: { code: 600 } });
    });
});

describe("reidentify", () => {
    it("sends the new subscriptions and resolves on OBS's Identified", async () => {
        const socket = await connected();
        const { Scenes, Inputs } = CatapultObs.EventSubscription;
        const done = CatapultObs.reidentify(Scenes | Inputs);
        expect(socket.lastSent(3)).toEqual({ eventSubscriptions: Scenes | Inputs });
        socket.serverSends(2, { negotiatedRpcVersion: 1 });
        await expect(done).resolves.toMatchObject({ negotiatedRpcVersion: 1 });
    });
});

describe("events", () => {
    const event = (eventType, eventData) => ({ eventType, eventIntent: 4, eventData });

    it("dispatches to listeners until they unsubscribe, across reconnects", async () => {
        const handler = vi.fn();
        const unsubscribe = CatapultObs.on("CurrentProgramSceneChanged", handler);
        let socket = await connected();
        socket.serverSends(5, event("CurrentProgramSceneChanged", { sceneName: "A" }));
        socket = await connected();
        socket.serverSends(5, event("CurrentProgramSceneChanged", { sceneName: "B" }));
        unsubscribe();
        socket.serverSends(5, event("CurrentProgramSceneChanged", { sceneName: "C" }));
        expect(handler.mock.calls).toEqual([
            [{ sceneName: "A" }, event("CurrentProgramSceneChanged", { sceneName: "A" })],
            [{ sceneName: "B" }, event("CurrentProgramSceneChanged", { sceneName: "B" })],
        ]);
    });

    it("feeds every event to * listeners and gives once() only the next one", async () => {
        const all = vi.fn();
        CatapultObs.on("*", all);
        const next = CatapultObs.once("StreamStateChanged");
        const socket = await connected();
        socket.serverSends(5, { eventType: "ExitStarted", eventIntent: 1 });
        socket.serverSends(5, event("StreamStateChanged", { outputActive: true }));
        socket.serverSends(5, event("StreamStateChanged", { outputActive: false }));
        await expect(next).resolves.toEqual({ outputActive: true });
        expect(all.mock.calls.map(([data, e]) => [e.eventType, data])).toEqual([
            ["ExitStarted", {}], ["StreamStateChanged", { outputActive: true }], ["StreamStateChanged", { outputActive: false }],
        ]);
    });
});

describe("page events", () => {
    function record() {
        const events = [];
        for (const type of ["catapult:obs:connected", "catapult:obs:disconnected"]) {
            document.addEventListener(type, (e) => events.push([type, e.detail]));
        }
        return events;
    }

    it("fires connected once ready, disconnected when a live connection ends, close() included", async () => {
        const events = record();
        let socket = await connected({}, ["GetVersion"]);
        expect(events).toEqual([["catapult:obs:connected", { info: CatapultObs.info() }]]);
        socket.serverCloses(4011);
        expect(events[1]).toEqual(["catapult:obs:disconnected", { error: expect.objectContaining({ code: 4011 }) }]);

        await connected();
        CatapultObs.close();
        expect(events.map(([type]) => type)).toEqual([
            "catapult:obs:connected", "catapult:obs:disconnected", "catapult:obs:connected", "catapult:obs:disconnected"]);
    });

    it("stays quiet on failed attempts", async () => {
        const events = record();
        const attempt = CatapultObs.connect({ host: "h", port: 1 });
        last().serverCloses(1006);
        await attempt.catch(() => {});
        CatapultObs.close();
        expect(events).toEqual([]);
    });
});

describe("debug", () => {
    afterEach(() => {
        localStorage.clear();
    });

    it("logs every frame with its direction while on", async () => {
        const log = vi.spyOn(console, "log").mockImplementation(() => {});
        CatapultObs.debug = true;
        const socket = await connected();
        CatapultObs.requests.getSceneList();

        const lines = log.mock.calls.map(([line, frame]) => [line, frame.op]);
        expect(lines).toEqual([
            ["[obs] ↓ op 0", 0], ["[obs] ↑ op 1", 1], ["[obs] ↓ op 2", 2], ["[obs] ↑ op 6", 6],
            ["[obs] ↓ op 7", 7], ["[obs] ↑ op 6", 6],
        ]);
        expect(log.mock.calls.at(-1)[1]).toEqual({ op: 6, d: socket.lastSent(6) });

        CatapultObs.debug = false;
        CatapultObs.requests.getSceneList();
        expect(log).toHaveBeenCalledTimes(6);
        log.mockRestore();
    });

    it("is remembered across reloads", async () => {
        CatapultObs.debug = true;
        await load("suggest", "obs");
        expect(CatapultObs.debug).toBe(true);
        CatapultObs.debug = false;
        await load("suggest", "obs");
        expect(CatapultObs.debug).toBe(false);
    });
});

describe("disconnects", () => {
    it("fires onDisconnect with the close code and fails pending requests when a live connection drops", async () => {
        CatapultObs.onDisconnect = vi.fn();
        const socket = await connected();
        const request = CatapultObs.call("GetSceneList");
        const waiting = CatapultObs.reidentify();
        socket.serverCloses(CatapultObs.CloseCode.SessionInvalidated);
        await expect(request).rejects.toMatchObject({ code: 4011 });
        await expect(waiting).rejects.toMatchObject({ code: 4011 });
        expect(CatapultObs.onDisconnect).toHaveBeenCalledWith(expect.objectContaining({ code: 4011 }));
        expect(CatapultObs.isConnected()).toBe(false);
    });

    it("stays silent on close(), even when the old socket's close event comes in late", async () => {
        CatapultObs.onDisconnect = vi.fn();
        const old = await connected();
        const request = expect(CatapultObs.call("GetSceneList")).rejects.toMatchObject({ code: 1000 });
        const fresh = await connected();
        await request;
        expect(old.closedWith).toBe(1000);
        old.serverCloses(1000);
        expect(CatapultObs.onDisconnect).not.toHaveBeenCalled();
        expect(CatapultObs.isConnected()).toBe(true);
        expect(fresh.closedWith).toBeNull();
    });
});

describe("debug capture", () => {
    /** Every entry debug-capture.js sent, once flushed. */
    function captured() {
        CatapultDebugCapture.flush();
        return fetch.mock.calls.flatMap(([, init]) => JSON.parse(init.body).entries);
    }

    beforeEach(async () => {
        vi.stubGlobal("fetch", vi.fn(async () => ({ status: 204 })));
        await load("debug-capture", "suggest", "obs");
        CatapultDebugCapture.enable({ url: "/me/debug/client-exchanges", source: "page" });
    });

    afterEach(() => {
        CatapultDebugCapture.disable();
        delete window.CatapultDebugCapture;
    });

    it("obsRequestIsCapturedWithItsStatus", async () => {
        const socket = await connected();
        const scenes = CatapultObs.call("GetSceneList");
        answer(socket, 7, { requestType: "GetSceneList", requestStatus: { result: true, code: 100 }, responseData: { scenes: [] } });
        await scenes;
        const failing = CatapultObs.call("SetCurrentProgramScene", { sceneName: "Nope" }).catch(() => {});
        answer(socket, 7, { requestType: "SetCurrentProgramScene", requestStatus: { result: false, code: 600, comment: "No source was found." } });
        await failing;
        const batch = CatapultObs.callBatch([{ requestType: "GetVersion" }]);
        answer(socket, 9, { results: [{ requestType: "GetVersion", requestStatus: { result: true, code: 100 } }] });
        await batch;

        const entries = captured();
        expect(entries.find(({ operation }) => operation === "GetSceneList")).toEqual(expect.objectContaining({
            service: "obs", direction: "OUT", status: 100, error: null, durationMs: expect.any(Number),
            request: expect.objectContaining({ requestType: "GetSceneList" }),
            response: expect.objectContaining({ responseData: { scenes: [] } }),
        }));
        expect(entries.find(({ operation }) => operation === "SetCurrentProgramScene"))
            .toEqual(expect.objectContaining({ status: 600, error: "No source was found." }));
        expect(entries.find(({ operation }) => operation === "RequestBatch"))
            .toEqual(expect.objectContaining({ direction: "OUT", error: null, response: expect.objectContaining({ results: expect.any(Array) }) }));
    });

    it("obsIdentifyIsCapturedWithoutAuthentication", async () => {
        const promise = CatapultObs.connect({ host: "h", port: 1, password: "hunter2" });
        last().serverSends(0, { rpcVersion: 1, authentication: { salt: "s", challenge: "c" } });
        await vi.waitFor(() => expect(last().sent).toHaveLength(1));
        const proof = last().lastSent(1).authentication;
        last().serverSends(2, { negotiatedRpcVersion: 1 });
        answerVersion(last());
        await promise;

        const entries = captured();
        expect(entries.slice(0, 4).map(({ direction, operation }) => [direction, operation])).toEqual([
            ["OUT", "Connect"], ["IN", "Hello"], ["OUT", "Identify"], ["IN", "Identified"]]);
        expect(entries[0].request).toEqual({ host: "h", port: 1, secure: false });
        expect(entries[2].request).toEqual({ rpcVersion: 1 });
        const sent = fetch.mock.calls.map(([, init]) => init.body).join();
        expect(sent).not.toContain("hunter2");
        expect(sent).not.toContain(proof);
    });

    it("captures the events, the close and the requests left without an answer", async () => {
        const socket = await connected();
        socket.serverSends(5, { eventType: "CurrentProgramSceneChanged", eventIntent: 4, eventData: { sceneName: "A" } });
        // Twitchat's messages are twitchat.js's to capture.
        socket.serverSends(5, { eventType: "CustomEvent", eventIntent: 1, eventData: { origin: "twitchat", type: "X" } });
        CatapultObs.requests.broadcastCustomEvent({ eventData: { origin: "twitchat", type: "Y" } }).catch(() => {});
        const request = CatapultObs.call("GetSceneList").catch(() => {});
        socket.serverCloses(1006, "gone");
        await request;

        // After Connect, Hello, Identify, Identified and GetVersion.
        const entries = captured().slice(5);
        expect(entries.map(({ direction, operation, error }) => [direction, operation, error])).toEqual([
            ["IN", "CurrentProgramSceneChanged", null], ["OUT", "GetSceneList", "connection closed"],
            ["IN", "ConnectionClosed", null]]);
        expect(entries[0].response).toEqual({ eventType: "CurrentProgramSceneChanged", eventIntent: 4, eventData: { sceneName: "A" } });
        expect(entries[2].response).toEqual({ code: 1006, reason: "gone" });

        await connected();
        CatapultObs.close();
        expect(captured().at(-1)).toEqual(expect.objectContaining({ direction: "OUT", operation: "Close" }));
    });

    it("captures only the shape of an input's settings", async () => {
        const socket = await connected();
        const settings = CatapultObs.call("GetInputSettings", { inputName: "Alerts" });
        answer(socket, 7, { requestType: "GetInputSettings", requestStatus: { result: true, code: 100 },
            responseData: { inputKind: "browser_source", inputSettings: { url: "https://streamlabs.com/alert-box/v3/TOKEN123" } } });
        await settings;
        socket.serverSends(5, { eventType: "InputSettingsChanged", eventIntent: 8,
            eventData: { inputName: "SRT", inputSettings: { input: "srt://10.0.0.5:9000?passphrase=s3cret" } } });

        const sent = (CatapultDebugCapture.flush(), fetch.mock.calls.map(([, init]) => init.body).join());
        expect(sent).not.toContain("TOKEN123");
        expect(sent).not.toContain("s3cret");
        expect(sent).not.toContain("streamlabs.com");
        expect(sent).not.toContain("10.0.0.5");
        expect(sent).toContain('"inputSettings":{"url":"string(44)"}');
        expect(sent).toContain('"inputSettings":{"input":"string(37)"}');
    });

    it("nothingIsCapturedOutsideDebug", async () => {
        CatapultDebugCapture.disable();
        const record = vi.spyOn(CatapultDebugCapture, "record");
        const pending = vi.spyOn(CatapultDebugCapture, "pending");
        const socket = await connected();
        socket.serverSends(5, { eventType: "StreamStateChanged", eventData: {} });
        socket.serverCloses(1006);
        CatapultDebugCapture.flush();
        expect(record).not.toHaveBeenCalled();
        expect(pending).not.toHaveBeenCalled();
        expect(fetch).not.toHaveBeenCalled();
    });

    it("keeps OBS working when the capture throws", async () => {
        vi.spyOn(CatapultDebugCapture, "pending").mockImplementation(() => { throw new Error("boom"); });
        vi.spyOn(CatapultDebugCapture, "record").mockImplementation(() => { throw new Error("boom"); });
        const socket = await connected();
        const scenes = CatapultObs.call("GetSceneList");
        answer(socket, 7, { requestType: "GetSceneList", requestStatus: { result: true, code: 100 }, responseData: { scenes: [] } });
        await expect(scenes).resolves.toEqual({ scenes: [] });
    });
});
