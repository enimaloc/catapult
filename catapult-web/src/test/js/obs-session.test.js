/*
 * obs-session.js against a stubbed CatapultObs and /me/obs: what gets connected, when it retries,
 * and how refresh() follows a settings change.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load, response } from "./helpers.js";

const CONFIG = { obsHost: "10.0.0.2", obsPort: 4456, obsPassword: "pw" };
const CONNECTION = { host: "10.0.0.2", port: 4456, password: "pw" };

let connects;
let connected;

function closedWith(code) {
    return Object.assign(new Error(`closed ${code}`), { code });
}

/** Loads obs-session.js with /me/obs answering `answer` and CatapultObs.connect() doing `outcome`. */
async function start(answer, outcome = async () => {}) {
    vi.stubGlobal("fetch", vi.fn(async () => answer));
    window.CatapultObs = {
        CloseCode: { AuthenticationFailed: 4009, UnsupportedRpcVersion: 4010 },
        connect: vi.fn(async (options) => {
            connects.push(options);
            await outcome(connects.length);
            connected = true;
        }),
        close: vi.fn(() => { connected = false; }),
        isConnected: () => connected,
        onDisconnect() {},
    };
    await load("obs-session");
    // flush() waits on a real setTimeout, which fake timers never fire.
    await vi.advanceTimersByTimeAsync(0);
}

beforeEach(() => {
    vi.useFakeTimers();
    connects = [];
    connected = false;
});

afterEach(() => {
    vi.useRealTimers();
});

describe("on page load", () => {
    it("connects with the logged-in user's OBS settings", async () => {
        await start(response(200, CONFIG));
        expect(fetch).toHaveBeenCalledWith("/me/obs", { cache: "no-store" });
        expect(connects).toEqual([CONNECTION]);
    });

    it("stays idle when logged out or OBS is disabled", async () => {
        await start(response(204));
        expect(connects).toEqual([]);
    });
});

describe("retries", () => {
    it("retries with a doubling delay while OBS is unreachable, then resets it", async () => {
        await start(response(200, CONFIG), async (n) => { if (n <= 3) throw closedWith(1006); });
        expect(connects).toHaveLength(1);
        await vi.advanceTimersByTimeAsync(1000);
        expect(connects).toHaveLength(2);
        await vi.advanceTimersByTimeAsync(1999);
        expect(connects).toHaveLength(2);
        await vi.advanceTimersByTimeAsync(1);
        expect(connects).toHaveLength(3);
        await vi.advanceTimersByTimeAsync(4000);
        expect(connects).toHaveLength(4);

        // Connected: a later drop starts again from 1s.
        CatapultObs.onDisconnect();
        await vi.advanceTimersByTimeAsync(1000);
        expect(connects).toHaveLength(5);
    });

    it("caps the delay at 30s", async () => {
        await start(response(200, CONFIG), async () => { throw closedWith(1006); });
        await vi.advanceTimersByTimeAsync(1000 + 2000 + 4000 + 8000 + 16000);
        expect(connects).toHaveLength(6);
        await vi.advanceTimersByTimeAsync(29999);
        expect(connects).toHaveLength(6);
        await vi.advanceTimersByTimeAsync(1);
        expect(connects).toHaveLength(7);
    });

    it("gives up on a wrong password", async () => {
        const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
        await start(response(200, CONFIG), async () => { throw closedWith(4009); });
        await vi.advanceTimersByTimeAsync(60000);
        expect(connects).toHaveLength(1);
        expect(warn).toHaveBeenCalled();
    });
});

describe("twitchat branch", () => {
    afterEach(() => {
        delete window.CatapultTwitchat;
    });

    it("hands the branch of /me/obs to twitchat.js on load and on every refresh", async () => {
        window.CatapultTwitchat = { configure: vi.fn() };
        await start(response(200, { ...CONFIG, twitchatBranch: "beta" }));
        expect(CatapultTwitchat.configure).toHaveBeenLastCalledWith("beta");

        fetch.mockResolvedValue(response(200, { ...CONFIG, twitchatBranch: "auto" }));
        await CatapultObsSession.refresh();
        expect(CatapultTwitchat.configure).toHaveBeenLastCalledWith("auto");
        expect(connects).toHaveLength(1);
    });

    it("detects it while OBS is disabled", async () => {
        window.CatapultTwitchat = { configure: vi.fn() };
        await start(response(204));
        expect(CatapultTwitchat.configure).toHaveBeenCalledWith("auto");
    });
});

describe("refresh", () => {
    it("keeps a live connection to unchanged settings", async () => {
        await start(response(200, CONFIG));
        CatapultObs.close.mockClear();
        await CatapultObsSession.refresh();
        expect(connects).toHaveLength(1);
        expect(CatapultObs.close).not.toHaveBeenCalled();
    });

    it("reconnects to changed settings and drops a pending retry of the old ones", async () => {
        await start(response(200, CONFIG), async (n) => { if (n === 1) throw closedWith(4009); });
        vi.mocked(fetch).mockResolvedValue(response(200, { ...CONFIG, obsPassword: "fixed" }));
        vi.spyOn(console, "warn").mockImplementation(() => {});

        await CatapultObsSession.refresh();
        expect(connects.at(-1)).toEqual({ ...CONNECTION, password: "fixed" });
        await vi.advanceTimersByTimeAsync(60000);
        expect(connects).toHaveLength(2);
    });

    it("disconnects once OBS got disabled, and stops retrying", async () => {
        await start(response(200, CONFIG), async (n) => { if (n === 1) throw closedWith(1006); });
        vi.mocked(fetch).mockResolvedValue(response(204));

        await CatapultObsSession.refresh();
        await vi.advanceTimersByTimeAsync(60000);
        expect(CatapultObs.close).toHaveBeenCalled();
        expect(connects).toHaveLength(1);
    });
});
