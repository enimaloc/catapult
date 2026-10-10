/*
 * obs.js against a fake WebSocket: each test drives the server side by hand (Hello, Identified,
 * responses, events, close) and checks what CatapultObs sends and resolves.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, load } from "./helpers.js";

let sockets;

class FakeSocket {
    constructor(url) {
        this.url = url;
        this.sent = [];
        this.closed = false;
        sockets.push(this);
    }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.closed = true; }
    serverSends(frame) { this.onmessage?.({ data: JSON.stringify(frame) }); }
    serverCloses() { this.onclose?.(); }
}

const last = () => sockets.at(-1);

async function connected(password) {
    const promise = CatapultObs.connect({ host: "localhost", port: 4455, password });
    last().serverSends({ op: 0, d: { rpcVersion: 1 } });
    await flush();
    last().serverSends({ op: 2, d: {} });
    await promise;
    return last();
}

beforeEach(async () => {
    sockets = [];
    vi.stubGlobal("WebSocket", FakeSocket);
    await load("obs");
});

describe("connect", () => {
    it("identifies without authentication when OBS asks for none", async () => {
        const socket = await connected();
        expect(socket.url).toBe("ws://localhost:4455");
        expect(socket.sent).toEqual([{ op: 1, d: { rpcVersion: 1 } }]);
        expect(CatapultObs.isConnected()).toBe(true);
    });

    it("answers the auth challenge with base64(sha256(base64(sha256(password + salt)) + challenge))", async () => {
        const promise = CatapultObs.connect({ host: "h", port: 1, password: "secret" });
        last().serverSends({ op: 0, d: { rpcVersion: 1, authentication: { salt: "s", challenge: "c" } } });
        await vi.waitFor(() => expect(last().sent).toHaveLength(1));
        // Reference value computed with the same algorithm through node:crypto.
        const { createHash } = await import("node:crypto");
        const b64 = (input) => createHash("sha256").update(input).digest("base64");
        expect(last().sent[0].d.authentication).toBe(b64(b64("secrets") + "c"));
        last().serverSends({ op: 2, d: {} });
        await promise;
    });

    it("rejects when OBS closes before Identified, as on a wrong password", async () => {
        const promise = CatapultObs.connect({ host: "h", port: 1, password: "bad" });
        last().serverCloses();
        await expect(promise).rejects.toThrow("Connection closed");
        expect(CatapultObs.isConnected()).toBe(false);
    });
});

describe("call", () => {
    it("resolves with responseData on success and rejects with the status otherwise", async () => {
        const socket = await connected();
        const ok = CatapultObs.call("GetSceneList", {});
        const ko = CatapultObs.call("GetInputSettings", { inputName: "x" });
        const [okReq, koReq] = socket.sent.slice(1).map((frame) => frame.d);
        expect(okReq).toMatchObject({ requestType: "GetSceneList", requestData: {} });
        socket.serverSends({ op: 7, d: { requestId: okReq.requestId, requestStatus: { result: true }, responseData: { scenes: [] } } });
        socket.serverSends({ op: 7, d: { requestId: koReq.requestId, requestStatus: { result: false, code: 600 } } });
        await expect(ok).resolves.toEqual({ scenes: [] });
        await expect(ko).rejects.toEqual({ result: false, code: 600 });
    });

    it("rejects right away when not connected", async () => {
        await expect(CatapultObs.call("GetSceneList")).rejects.toThrow("Not connected");
    });
});

describe("events", () => {
    it("dispatches to listeners until they unsubscribe, across reconnects", async () => {
        const handler = vi.fn();
        CatapultObs.on("CurrentProgramSceneChanged", handler);
        let socket = await connected();
        socket.serverSends({ op: 5, d: { eventType: "CurrentProgramSceneChanged", eventData: { sceneName: "A" } } });
        socket = await connected();
        socket.serverSends({ op: 5, d: { eventType: "CurrentProgramSceneChanged", eventData: { sceneName: "B" } } });
        CatapultObs.off("CurrentProgramSceneChanged", handler);
        socket.serverSends({ op: 5, d: { eventType: "CurrentProgramSceneChanged", eventData: { sceneName: "C" } } });
        expect(handler.mock.calls).toEqual([[{ sceneName: "A" }], [{ sceneName: "B" }]]);
    });
});

describe("disconnects", () => {
    it("fires onDisconnect and fails pending requests when a live connection drops", async () => {
        CatapultObs.onDisconnect = vi.fn();
        const socket = await connected();
        const request = CatapultObs.call("GetSceneList");
        socket.serverCloses();
        await expect(request).rejects.toThrow("Connection closed");
        expect(CatapultObs.onDisconnect).toHaveBeenCalledOnce();
        expect(CatapultObs.isConnected()).toBe(false);
    });

    it("stays silent on close(), even when the old socket's close event comes in late", async () => {
        CatapultObs.onDisconnect = vi.fn();
        const old = await connected();
        const onclose = old.onclose;
        const fresh = await connected();
        expect(old.closed).toBe(true);
        onclose?.();
        expect(CatapultObs.onDisconnect).not.toHaveBeenCalled();
        expect(CatapultObs.isConnected()).toBe(true);
        expect(fresh.closed).toBe(false);
    });
});
