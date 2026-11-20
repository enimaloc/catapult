/*
 * debug-capture.js: the queue of a member's OBS and Twitchat exchanges, while in debug mode, sent
 * by batches to catapult-web (/me/debug/client-exchanges or the widget's own URL).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load, response } from "./helpers.js";

const PAGE = { url: "/me/debug/client-exchanges", source: "page" };
const WIDGET = { url: "/widget/obs/w-secret/debug/client-exchanges", source: "widget" };

/** An entry as obs.js or twitchat.js records it. */
const entry = (operation = "GetSceneList", service = "obs") => ({ service, direction: "IN", operation, response: { n: 1 } });

/** The batches posted so far. */
const batches = () => fetch.mock.calls.map(([, init]) => JSON.parse(init.body));

beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-20T10:00:00Z"));
    vi.stubGlobal("fetch", vi.fn(async () => response(204)));
    document.cookie = "XSRF-TOKEN=csrf-1";
    window.CatapultCsrf = { readCookie: () => "csrf-1" };
    await load("debug-capture");
});

afterEach(() => {
    CatapultDebugCapture.disable();
    delete window.CatapultDebugCapture;
    delete document.body.dataset.debugCapture;
    vi.useRealTimers();
});

describe("activation", () => {
    it("doesNothingUntilEnabled", async () => {
        expect(CatapultDebugCapture.active()).toBe(false);
        CatapultDebugCapture.record(entry());
        CatapultDebugCapture.pending("obs:req-1", entry());
        CatapultDebugCapture.settle("obs:req-1", { status: 100 });
        await vi.advanceTimersByTimeAsync(60000);
        CatapultDebugCapture.flush();
        expect(fetch).not.toHaveBeenCalled();
    });

    it("enables itself on a page rendered in debug mode", async () => {
        document.body.dataset.debugCapture = "true";
        await load("debug-capture");
        expect(CatapultDebugCapture.active()).toBe(true);
        CatapultDebugCapture.record(entry());
        CatapultDebugCapture.flush();
        expect(fetch).toHaveBeenCalledWith("/me/debug/client-exchanges", expect.objectContaining({
            method: "POST",
            headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": "csrf-1" },
        }));
    });

    it("drops what it queued once disabled", async () => {
        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.record(entry());
        CatapultDebugCapture.disable();
        await vi.advanceTimersByTimeAsync(10000);
        expect(fetch).not.toHaveBeenCalled();
        expect(CatapultDebugCapture.active()).toBe(false);
    });

    it("keeps its queue when enabled again with the same target", async () => {
        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.record(entry());
        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.flush();
        expect(batches()[0].entries).toHaveLength(1);
    });
});

describe("batches", () => {
    it("flushesEvery5sOrAt50", async () => {
        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.record(entry());
        await vi.advanceTimersByTimeAsync(4999);
        expect(fetch).not.toHaveBeenCalled();
        await vi.advanceTimersByTimeAsync(1);
        expect(batches()).toEqual([{
            source: "page",
            sentAt: "2026-11-20T10:00:05.000Z",
            entries: [{
                service: "obs", direction: "IN", operation: "GetSceneList", at: "2026-11-20T10:00:00.000Z",
                durationMs: null, status: null, request: null, response: { n: 1 }, error: null,
            }],
        }]);

        for (let i = 0; i < 50; i++) CatapultDebugCapture.record(entry(`Op${i}`));
        expect(fetch).toHaveBeenCalledTimes(2);
        expect(batches()[1].entries).toHaveLength(50);
        await vi.advanceTimersByTimeAsync(5000);
        expect(fetch).toHaveBeenCalledTimes(2);
    });

    it("sends the widget's batches without a CSRF header", async () => {
        CatapultDebugCapture.enable(WIDGET);
        CatapultDebugCapture.record(entry());
        CatapultDebugCapture.flush();
        expect(fetch).toHaveBeenCalledWith(WIDGET.url, expect.objectContaining({
            headers: { "Content-Type": "application/json" },
        }));
        expect(batches()[0].source).toBe("widget");
    });

    it("splits a batch over the size bound and truncates an oversized body", async () => {
        CatapultDebugCapture.enable(PAGE);
        const big = "x".repeat(60000);
        for (let i = 0; i < 6; i++) CatapultDebugCapture.record({ ...entry(), response: { big } });
        CatapultDebugCapture.record({ ...entry(), response: { huge: "y".repeat(70000) } });
        CatapultDebugCapture.flush();
        const sent = fetch.mock.calls.map(([, init]) => init.body);
        expect(sent.length).toBeGreaterThan(1);
        sent.forEach((body) => expect(new TextEncoder().encode(body).length).toBeLessThanOrEqual(256 * 1024));
        const entries = batches().flatMap((batch) => batch.entries);
        expect(entries).toHaveLength(7);
        expect(entries[6].response.truncated).toMatch(/^\{"huge":"y+…\(\+\d+ chars\)$/);
    });

    it("splits what is left on pagehide into batches a beacon can carry", async () => {
        const beacon = vi.fn(() => true);
        vi.stubGlobal("navigator", { sendBeacon: beacon });
        CatapultDebugCapture.enable(WIDGET);
        for (let i = 0; i < 6; i++) CatapultDebugCapture.record({ ...entry(), response: { big: "x".repeat(30000) } });
        window.dispatchEvent(new Event("pagehide"));
        expect(beacon.mock.calls.length).toBeGreaterThanOrEqual(3);
        for (const [, blob] of beacon.mock.calls) expect(blob.size).toBeLessThanOrEqual(60 * 1024);
    });

    it("flushes on pagehide with a beacon on the widget, a keepalive fetch on the page", async () => {
        const beacon = vi.fn(() => true);
        vi.stubGlobal("navigator", { sendBeacon: beacon });
        CatapultDebugCapture.enable(WIDGET);
        CatapultDebugCapture.record(entry());
        window.dispatchEvent(new Event("pagehide"));
        expect(beacon).toHaveBeenCalledWith(WIDGET.url, expect.any(Blob));
        expect(fetch).not.toHaveBeenCalled();

        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.record(entry());
        window.dispatchEvent(new Event("pagehide"));
        expect(fetch).toHaveBeenCalledWith(PAGE.url, expect.objectContaining({ keepalive: true }));
    });

    it("capsAt300PerMinuteThenOneDroppedEntryPerService", async () => {
        CatapultDebugCapture.enable(PAGE);
        for (let i = 0; i < 310; i++) CatapultDebugCapture.record(entry());
        for (let i = 0; i < 5; i++) CatapultDebugCapture.record(entry("TRIGGER_LIST", "twitchat"));
        await vi.advanceTimersByTimeAsync(59000);
        expect(batches().flatMap((batch) => batch.entries)).toHaveLength(300);

        // Sent once the minute is over, with nothing coming after.
        await vi.advanceTimersByTimeAsync(1000);
        expect(batches().at(-1).entries).toEqual([
            expect.objectContaining({ service: "obs", direction: "IN", operation: "dropped", request: null,
                response: { dropped: 10 }, at: "2026-11-20T10:00:00.000Z" }),
            expect.objectContaining({ service: "twitchat", direction: "IN", operation: "dropped", request: null,
                response: { dropped: 5 } }),
        ]);

        CatapultDebugCapture.record(entry("Later"));
        CatapultDebugCapture.flush();
        expect(batches().at(-1).entries).toEqual([expect.objectContaining({ operation: "Later" })]);
    });
});

describe("answers", () => {
    it("stopsOn409", async () => {
        fetch.mockResolvedValue(response(409));
        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.record(entry());
        CatapultDebugCapture.flush();
        await vi.advanceTimersByTimeAsync(0);
        expect(CatapultDebugCapture.active()).toBe(false);
        CatapultDebugCapture.record(entry());
        CatapultDebugCapture.flush();
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    it("dropsABatchOnOtherFailures", async () => {
        CatapultDebugCapture.enable(PAGE);
        for (const failure of [async () => response(429), async () => response(400), async () => { throw new TypeError("down"); }]) {
            fetch.mockImplementationOnce(failure);
            CatapultDebugCapture.record(entry());
            CatapultDebugCapture.flush();
            await vi.advanceTimersByTimeAsync(0);
        }
        expect(CatapultDebugCapture.active()).toBe(true);
        CatapultDebugCapture.record(entry("Next"));
        CatapultDebugCapture.flush();
        expect(batches().map((batch) => batch.entries.map(({ operation }) => operation)))
            .toEqual([["GetSceneList"], ["GetSceneList"], ["GetSceneList"], ["Next"]]);
    });
});

describe("pairing", () => {
    it("pairsARequestWithItsResponse", async () => {
        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.pending("obs:req-1", { service: "obs", direction: "OUT", operation: "GetSceneList", request: { a: 1 } });
        await vi.advanceTimersByTimeAsync(120);
        CatapultDebugCapture.settle("obs:req-1", { status: 100, response: { scenes: [] } });
        CatapultDebugCapture.settle("obs:req-1", { status: 100 });
        CatapultDebugCapture.flush();
        expect(batches()[0].entries).toEqual([{
            service: "obs", direction: "OUT", operation: "GetSceneList", at: "2026-11-20T10:00:00.000Z",
            durationMs: 120, status: 100, request: { a: 1 }, response: { scenes: [] }, error: null,
        }]);
    });

    it("holds at most 1000 requests waiting for their answer", async () => {
        CatapultDebugCapture.enable(PAGE);
        for (let i = 0; i <= 1000; i++) CatapultDebugCapture.pending(`obs:req-${i}`, entry(`Op${i}`));
        CatapultDebugCapture.settle("obs:req-1000", {});
        CatapultDebugCapture.settle("obs:req-999", {});
        CatapultDebugCapture.flush();
        expect(batches()[0].entries.map(({ operation }) => operation)).toEqual(["Op999"]);
    });

    it("settlesPendingRequestsAsConnectionClosed", async () => {
        CatapultDebugCapture.enable(PAGE);
        CatapultDebugCapture.pending("obs:req-1", { service: "obs", direction: "OUT", operation: "A" });
        CatapultDebugCapture.pending("obs:req-2", { service: "obs", direction: "OUT", operation: "B" });
        CatapultDebugCapture.pending("twitchat:x", { service: "twitchat", direction: "OUT", operation: "C" });
        CatapultDebugCapture.settleAll("obs", { error: "connection closed" });
        CatapultDebugCapture.flush();
        expect(batches()[0].entries.map(({ operation, error }) => [operation, error]))
            .toEqual([["A", "connection closed"], ["B", "connection closed"]]);
    });
});

describe("secrets", () => {
    it("stripsTheObsAuthentication", () => {
        const identify = { op: 1, d: { rpcVersion: 1, authentication: "proof" } };
        expect(CatapultDebugCapture.stripObs(identify)).toEqual({ op: 1, d: { rpcVersion: 1 } });
        expect(identify.d.authentication).toBe("proof");
        const settings = { requestType: "GetStreamServiceSettings",
            responseData: { streamServiceSettings: { server: "rtmp://x", key: "live_123", password: "pw" } } };
        expect(CatapultDebugCapture.stripObs(settings).responseData.streamServiceSettings)
            .toEqual({ server: "rtmp://x", key: "<redacted>", password: "<redacted>" });
    });

    it("redacts every member a credential's name, whatever its value", () => {
        expect(CatapultDebugCapture.stripObs({ requestData: {
            password: 1234, apiKey: { a: 1 }, bearer_token: "b", clientSecret: ["s"], passphrase: "p", Authorization: "x",
            access_token: "t", token_type: "Bearer", sceneName: "Main", empty: null, nothing: null, code: 100,
        } }).requestData).toEqual({
            password: "<redacted>", apiKey: "<redacted>", bearer_token: "<redacted>", clientSecret: "<redacted>",
            passphrase: "<redacted>", Authorization: "<redacted>", access_token: "<redacted>", token_type: "Bearer",
            sceneName: "Main", empty: null, nothing: null, code: 100,
        });
    });

    it("redacts the whole stream service settings but which service and its server", () => {
        for (const requestType of ["GetStreamServiceSettings", "SetStreamServiceSettings"]) {
            const field = requestType.startsWith("Get") ? "responseData" : "requestData";
            const stripped = CatapultDebugCapture.stripObs({ requestType, [field]: { streamServiceType: "rtmp_custom",
                streamServiceSettings: { service: "Twitch", server: "rtmp://x/app", username: "u", use_auth: true, bwtest: false } } });
            expect(stripped[field]).toEqual({ streamServiceType: "rtmp_custom", streamServiceSettings: {
                service: "Twitch", server: "rtmp://x/app", username: "<redacted>", use_auth: "<redacted>", bwtest: "<redacted>" } });
        }
    });

    it("redacts profile parameters' values", () => {
        expect(CatapultDebugCapture.stripObs({ requestType: "GetProfileParameter",
            responseData: { parameterValue: "live_123", defaultParameterValue: null } }).responseData)
            .toEqual({ parameterValue: "<redacted>", defaultParameterValue: null });
        expect(CatapultDebugCapture.stripObs({ requestType: "SetProfileParameter",
            requestData: { parameterCategory: "Stream", parameterName: "Key", parameterValue: "live_123" } }).requestData)
            .toEqual({ parameterCategory: "Stream", parameterName: "Key", parameterValue: "<redacted>" });
    });

    it("redacts persistent data values", () => {
        expect(CatapultDebugCapture.stripObs({ requestType: "SetPersistentData",
            requestData: { realm: "R", slotName: "s", slotValue: { plugin: "secret" } } }).requestData)
            .toEqual({ realm: "R", slotName: "s", slotValue: "<redacted>" });
        expect(CatapultDebugCapture.stripObs({ requestType: "GetPersistentData", responseData: { slotValue: [1] } }).responseData)
            .toEqual({ slotValue: "<redacted>" });
    });

    it("redacts vendor requests' and events' bodies", () => {
        expect(CatapultDebugCapture.stripObs({ requestType: "CallVendorRequest",
            requestData: { vendorName: "v", requestType: "r", requestData: { x: 1 } },
            responseData: { vendorName: "v", requestType: "r", responseData: { y: 2 } } })).toEqual({ requestType: "CallVendorRequest",
            requestData: { vendorName: "v", requestType: "r", requestData: "<redacted>", responseData: undefined },
            responseData: { vendorName: "v", requestType: "r", requestData: undefined, responseData: "<redacted>" } });
        expect(CatapultDebugCapture.stripObs({ eventType: "VendorEvent", eventData: { vendorName: "v", eventType: "e", eventData: { z: 3 } } }))
            .toEqual({ eventType: "VendorEvent", eventData: { vendorName: "v", eventType: "e", eventData: "<redacted>" } });
    });

    it("applies the type rules inside a request batch", () => {
        const stripped = CatapultDebugCapture.stripObs({ requests: [
            { requestType: "SetProfileParameter", requestData: { parameterValue: "k" } },
            { requestType: "GetSceneList" }] });
        expect(stripped.requests[0].requestData.parameterValue).toBe("<redacted>");
    });

    it("stripsTwitchatActionTokens", () => {
        const token = "0b6f2a4e-1c3d-4e5f-8a9b-0c1d2e3f4a5b";
        const message = { origin: "twitchat", id: "11111111-2222-4333-8444-555555555555", type: "CUSTOM_CHAT_MESSAGE",
            data: { message: `Ban? https://c.example/twitchat/action/${token}`, actions: [{ id: token }], [token]: 1 } };
        const stripped = CatapultDebugCapture.stripTwitchat(message);
        expect(JSON.stringify(stripped)).not.toContain(token);
        expect(stripped.id).toBe(message.id);
        expect(stripped.data.message).toBe("Ban? https://c.example/twitchat/action/<redacted>");
        expect(message.data.actions[0].id).toBe(token);
        // Inside an OBS message too (a custom event carrying a Twitchat message).
        expect(JSON.stringify(CatapultDebugCapture.stripObs({ eventData: message }))).not.toContain(token);
    });

    it("strips the widget token from any string it sends", async () => {
        CatapultDebugCapture.enable(WIDGET);
        CatapultDebugCapture.record({ ...entry(), response: { url: "https://c.example/widget/obs/w-secret?x=1" },
            error: "failed /widget/obs/w-secret/config" });
        CatapultDebugCapture.flush();
        const body = fetch.mock.calls[0][1].body;
        expect(body).not.toContain("w-secret");
        expect(batches()[0].entries[0].response.url).toBe("https://c.example/<redacted>?x=<redacted>");
    });

    it("keeps Catapult's own URLs but for the widget token, percent-encoded too", async () => {
        CatapultDebugCapture.enable(PAGE);
        const own = `${location.origin}/widget/obs/w-1?x=1`;
        CatapultDebugCapture.record({ ...entry(), response: { own, mine: `${location.origin}/channel/me?tab=obs`,
            encoded: `see ${encodeURIComponent(own)}`, upper: "a%2Fwidget%2Fobs%2Fw-2%2Fconfig" } });
        CatapultDebugCapture.flush();
        expect(batches()[0].entries[0].response).toEqual({
            own: `${location.origin}/widget/obs/<redacted>?x=1`,
            mine: `${location.origin}/channel/me?tab=obs`,
            encoded: `see ${encodeURIComponent(location.origin)}%2Fwidget%2Fobs%2F<redacted>%3Fx%3D1`,
            upper: "a%2Fwidget%2Fobs%2F<redacted>%2Fconfig",
        });
    });

    it("removes the widget's own token wherever it appears", async () => {
        CatapultDebugCapture.enable(WIDGET);
        CatapultDebugCapture.record({ ...entry(), response: { note: "token w-secret, w%2Dsecret" }, operation: "w-secret" });
        CatapultDebugCapture.flush();
        expect(fetch.mock.calls[0][1].body).not.toContain("w-secret");
    });

    it("strips userinfo, path, query values and fragment of a foreign URL", async () => {
        CatapultDebugCapture.enable(PAGE);
        const settings = CatapultDebugCapture.stripObs({ requestType: "GetInputSettings", responseData: { inputSettings: {
            url: "https://streamlabs.com/alert-box/v3/ABCDEF123456?layout=1#x",
            input: "srt://user:hunter2@10.0.0.5:9000?passphrase=s3cret&latency=200",
            server: "rtmp://live.example.tv/app/live_123",
            css: "body { background: url(https://cdn.example/u/abc.png); }",
            bare: "https://example.com/",
            broken: "foo://[bad",
        } } });
        CatapultDebugCapture.record({ ...entry(), response: settings });
        CatapultDebugCapture.flush();
        const body = fetch.mock.calls[0][1].body;
        for (const secret of ["ABCDEF123456", "hunter2", "s3cret", "live_123", "abc.png", "[bad"]) expect(body).not.toContain(secret);
        expect(batches()[0].entries[0].response.responseData.inputSettings).toEqual({
            url: "https://streamlabs.com/<redacted>?layout=<redacted>#<redacted>",
            input: "srt://10.0.0.5:9000?passphrase=<redacted>&latency=<redacted>",
            server: "rtmp://live.example.tv/<redacted>",
            css: "body { background: url(https://cdn.example/<redacted>); }",
            bare: "https://example.com/",
            broken: "foo://<redacted>",
        });
    });

    it("aThrowingHookDoesNotBreakTheCaller", () => {
        CatapultDebugCapture.enable(PAGE);
        const circular = {};
        circular.self = circular;
        expect(() => CatapultDebugCapture.record({ ...entry(), response: circular })).not.toThrow();
        expect(() => CatapultDebugCapture.pending("k", { ...entry(), request: circular })).not.toThrow();
        expect(() => CatapultDebugCapture.settle("k", { response: circular })).not.toThrow();
        expect(() => CatapultDebugCapture.stripObs(circular)).not.toThrow();
        expect(() => CatapultDebugCapture.stripTwitchat(circular)).not.toThrow();
        fetch.mockImplementation(() => { throw new Error("boom"); });
        expect(() => CatapultDebugCapture.flush()).not.toThrow();
    });
});
