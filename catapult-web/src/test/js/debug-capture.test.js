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
    it("keeps only the non-secret members of OBS's opening messages", () => {
        const identify = { rpcVersion: 1, authentication: "proof", eventSubscriptions: 33, extra: "x-secret" };
        expect(CatapultDebugCapture.stripObs(identify)).toEqual({ rpcVersion: 1, eventSubscriptions: 33, extra: "string(8)" });
        expect(identify.authentication).toBe("proof");
        expect(CatapultDebugCapture.stripObs({ obsWebSocketVersion: "5.5.0", rpcVersion: 1,
            authentication: { challenge: "c", salt: "s" } })).toEqual({ obsWebSocketVersion: "5.5.0", rpcVersion: 1 });
        expect(CatapultDebugCapture.stripObs({ negotiatedRpcVersion: 1 })).toEqual({ negotiatedRpcVersion: 1 });
    });

    it("redacts every member a credential's name, whatever its value, in kept OBS data", () => {
        expect(CatapultDebugCapture.stripObs({ requestType: "SetCurrentProgramScene", requestData: {
            password: 1234, apiKey: { a: 1 }, bearer_token: "b", clientSecret: ["s"], passphrase: "p", Authorization: "x",
            access_token: "t", token_type: "Bearer", sceneName: "Main", empty: null, nothing: null, code: 100,
        } }).requestData).toEqual({
            password: "<redacted>", apiKey: "<redacted>", bearer_token: "<redacted>", clientSecret: "<redacted>",
            passphrase: "<redacted>", Authorization: "<redacted>", access_token: "<redacted>", token_type: "Bearer",
            sceneName: "Main", empty: null, nothing: null, code: 100,
        });
    });

    describe("OBS data kept for the allowed requests and events", () => {
        const kept = (message) => expect(CatapultDebugCapture.stripObs(message)).toEqual(message);

        it("keeps the version and stats", () => {
            kept({ requestType: "GetVersion", requestId: "req-1", requestStatus: { result: true, code: 100 },
                responseData: { obsVersion: "31.0.0", rpcVersion: 1, availableRequests: ["GetSceneList"], platform: "windows" } });
            kept({ requestType: "GetStats", requestId: "req-2", responseData: { cpuUsage: 1.5, activeFps: 60 } });
        });

        it("keeps scenes and scene items", () => {
            kept({ requestType: "GetSceneList", responseData: { currentProgramSceneName: "Main", scenes: [{ sceneName: "Main", sceneIndex: 0 }] } });
            kept({ requestType: "SetCurrentProgramScene", requestData: { sceneName: "BRB" } });
            kept({ requestType: "GetSceneItemList", requestData: { sceneName: "Main" },
                responseData: { sceneItems: [{ sceneItemId: 3, sourceName: "Cam", sceneItemEnabled: true }] } });
            kept({ requestType: "SetSceneItemEnabled", requestData: { sceneName: "Main", sceneItemId: 3, sceneItemEnabled: false } });
            kept({ eventType: "CurrentProgramSceneChanged", eventIntent: 4, eventData: { sceneName: "A" } });
            kept({ eventType: "SceneItemEnableStateChanged", eventIntent: 128, eventData: { sceneName: "A", sceneItemId: 1, sceneItemEnabled: true } });
        });

        it("keeps the input list's names and kinds", () => {
            kept({ requestType: "GetInputList", responseData: { inputs: [{ inputName: "Alerts", inputKind: "browser_source" }] } });
            kept({ eventType: "InputNameChanged", eventData: { oldInputName: "a", inputName: "b" } });
        });

        it("keeps the stream, the recording and the transitions", () => {
            kept({ requestType: "GetStreamStatus", responseData: { outputActive: true, outputDuration: 1000 } });
            kept({ requestType: "ToggleRecord", responseData: { outputActive: false } });
            kept({ requestType: "SetCurrentSceneTransition", requestData: { transitionName: "Fade" } });
            kept({ eventType: "StreamStateChanged", eventData: { outputActive: true, outputState: "OBS_WEBSOCKET_OUTPUT_STARTED" } });
            kept({ eventType: "ExitStarted", eventData: {} });
        });

        it("keeps a created input but for the shape of its settings", () => {
            expect(CatapultDebugCapture.stripObs({ requestType: "CreateInput", requestData: { sceneName: "Main", inputName: "Alerts",
                inputKind: "browser_source", inputSettings: { url: "overlay.example/alerts/SECRET1", width: 800 } } }).requestData)
                .toEqual({ sceneName: "Main", inputName: "Alerts", inputKind: "browser_source",
                    inputSettings: { url: "string(30)", width: "number" } });
            expect(JSON.stringify(CatapultDebugCapture.stripObs({ eventType: "InputCreated", eventData: { inputName: "A",
                inputSettings: { url: "SECRET2" }, defaultInputSettings: { url: "SECRET3" } } }))).not.toMatch(/SECRET/);
        });
    });

    describe("OBS data reduced to its shape for any other request or event", () => {
        /** The message stripped, none of `secrets` left in it. */
        function shaped(message, secrets) {
            const stripped = CatapultDebugCapture.stripObs(message);
            const json = JSON.stringify(stripped);
            for (const secret of secrets) expect(json).not.toContain(secret);
            return stripped;
        }

        it("keeps no value of an input's settings, a scheme-less overlay URL included", () => {
            const stripped = shaped({ requestType: "GetInputSettings", requestId: "req-1", requestStatus: { result: true, code: 100 },
                responseData: { inputKind: "browser_source", inputSettings: { url: "streamlabs.com/alert-box/v3/ABCDEF123456", fps: 30, shutdown: true } } },
            ["ABCDEF123456", "streamlabs", "browser_source"]);
            expect(stripped).toEqual({ requestType: "GetInputSettings", requestId: "req-1", requestStatus: { result: true, code: 100 },
                responseData: { inputKind: "string(14)", inputSettings: { url: "string(40)", fps: "number", shutdown: "boolean" } } });
            shaped({ requestType: "SetInputSettings", requestData: { inputName: "SRT", inputSettings: { input: "s3cret-host:9000" } } }, ["s3cret"]);
            shaped({ eventType: "InputSettingsChanged", eventData: { inputName: "SRT", inputSettings: { input: "s3cret-host:9000" } } }, ["s3cret"]);
        });

        it("keeps no value of a JSON written in a string", () => {
            const stripped = shaped({ requestType: "SetPersistentData", requestData: { realm: "OBS_WEBSOCKET_DATA_REALM_GLOBAL",
                slotName: "twitchat", slotValue: "{\"apiToken\":\"tk-777\"}" } }, ["tk-777", "apiToken"]);
            expect(stripped.requestData.slotValue).toBe("string(21)");
        });

        it("keeps no value of a filter's settings, its string arrays included", () => {
            const stripped = shaped({ requestType: "GetSourceFilterSettings", responseData: { filterSettings: {
                words: ["alpha-secret", "beta-secret"], nested: [{ key: "gamma-secret" }], none: [] } } }, ["-secret"]);
            expect(stripped.responseData.filterSettings).toEqual({ words: "array(2)", nested: [{ key: "string(12)" }], none: "array(0)" });
            shaped({ requestType: "SetSourceFilterSettings", requestData: { filterSettings: { url: "x-secret" } } }, ["x-secret"]);
            shaped({ eventType: "SourceFilterSettingsChanged", eventData: { filterSettings: { url: "x-secret" } } }, ["x-secret"]);
        });

        it("keeps no value of a percent-encoded URL", () => {
            const encoded = encodeURIComponent("https://overlay.example/u/SECRET9?k=v");
            shaped({ requestType: "SetProfileParameter", requestData: { parameterCategory: "Stream", parameterValue: encoded } }, ["SECRET9", "overlay"]);
        });

        it("keeps no value of a custom event not from Twitchat", () => {
            shaped({ eventType: "CustomEvent", eventData: { origin: "streamerbot", secret: "sb-111", data: { pwd: "sb-222" } } }, ["sb-1", "sb-2", "streamerbot"]);
            shaped({ requestType: "BroadcastCustomEvent", requestData: { eventData: { origin: "other", value: "sb-333" } } }, ["sb-3", "other"]);
        });

        it("keeps no value of stream service settings, profile parameters, vendor messages and unknown types", () => {
            shaped({ requestType: "GetStreamServiceSettings", responseData: { streamServiceType: "rtmp_custom",
                streamServiceSettings: { server: "rtmp://x/app", key: "live_123" } } }, ["live_123", "rtmp"]);
            shaped({ requestType: "GetProfileParameter", responseData: { parameterValue: "live_456" } }, ["live_456"]);
            shaped({ requestType: "CallVendorRequest", requestData: { vendorName: "v-1", requestData: { x: "v-2" } } }, ["v-1", "v-2"]);
            shaped({ eventType: "VendorEvent", eventData: { vendorName: "v-3", eventData: { z: "v-4" } } }, ["v-3", "v-4"]);
            shaped({ requestType: "SomeFutureRequest", requestData: { anything: "f-1" }, other: "f-2" }, ["f-1", "f-2"]);
            expect(CatapultDebugCapture.stripObs(null)).toBeNull();
            expect(CatapultDebugCapture.stripObs("raw")).toBe("string(3)");
        });

        it("applies the same rules inside a request batch and its results", () => {
            const stripped = shaped({ requestId: "req-9", haltOnFailure: true, requests: [
                { requestType: "GetInputSettings", requestData: { inputName: "Alerts" } },
                { requestType: "SetProfileParameter", requestData: { parameterValue: "k-secret" } },
                { requestType: "GetSceneList" }] }, ["k-secret", "Alerts"]);
            expect(stripped.requests[2]).toEqual({ requestType: "GetSceneList" });
            expect(stripped.haltOnFailure).toBe(true);
            const results = shaped({ requestId: "req-9", results: [
                { requestType: "GetInputSettings", requestStatus: { result: true, code: 100 }, responseData: { inputSettings: { url: "r-secret" } } },
                { requestType: "GetCurrentProgramScene", requestStatus: { result: true, code: 100 }, responseData: { sceneName: "Main" } }] }, ["r-secret"]);
            expect(results.results[1].responseData).toEqual({ sceneName: "Main" });
        });
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
        const carried = CatapultDebugCapture.stripObs({ eventType: "CustomEvent", eventData: message });
        expect(JSON.stringify(carried)).not.toContain(token);
        expect(carried.eventData.data.message).toBe("Ban? https://c.example/twitchat/action/<redacted>");
        const broadcast = CatapultDebugCapture.stripObs({ requestType: "BroadcastCustomEvent", requestData: { eventData: message } });
        expect(broadcast.requestData.eventData.type).toBe("CUSTOM_CHAT_MESSAGE");
        expect(JSON.stringify(broadcast)).not.toContain(token);
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
        const settings = CatapultDebugCapture.stripObs({ requestType: "GetCurrentProgramScene", responseData: { inputSettings: {
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
