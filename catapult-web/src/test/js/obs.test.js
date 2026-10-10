/*
 * obs.js against a fake WebSocket: each test drives the server side by hand (Hello, Identified,
 * responses, events, close) and checks what CatapultObs sends and resolves.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
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

async function connected(options = {}) {
    const promise = CatapultObs.connect({ host: "localhost", port: 4455, ...options });
    last().serverSends(0, { obsWebSocketVersion: "5.5.0", rpcVersion: 1 });
    await flush();
    last().serverSends(2, { negotiatedRpcVersion: 1 });
    await promise;
    return last();
}

/** Answers the last request sent on socket. */
function answer(socket, op, d) {
    const requestId = socket.sent.filter((frame) => frame.op === op - 1).at(-1).d.requestId;
    socket.serverSends(op, { requestId, ...d });
}

beforeEach(async () => {
    sockets = [];
    vi.stubGlobal("WebSocket", FakeSocket);
    await load("obs");
});

describe("connect", () => {
    it("identifies over the json subprotocol and resolves with what OBS announced", async () => {
        const promise = CatapultObs.connect({ host: "localhost", port: 4455 });
        const socket = last();
        socket.serverSends(0, { obsWebSocketVersion: "5.5.0", rpcVersion: 1 });
        await flush();
        socket.serverSends(2, { negotiatedRpcVersion: 1 });

        await expect(promise).resolves.toEqual(
            { obsWebSocketVersion: "5.5.0", rpcVersion: 1, authenticated: false, negotiatedRpcVersion: 1 });
        expect(socket.url).toBe("ws://localhost:4455");
        expect(socket.protocol).toBe("obswebsocket.json");
        expect(socket.sent).toEqual([{ op: 1, d: { rpcVersion: 1 } }]);
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
        await expect(promise).resolves.toMatchObject({ authenticated: true });
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
