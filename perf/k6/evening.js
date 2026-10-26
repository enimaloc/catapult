// A busy evening on catapult (perf/run.sh's load run): STREAMERS streamers working on their channel page
// and VIEWERS viewers whose stream overlay polls the streamer's game, for DURATION.
//
// Run by perf/run.sh in grafana/k6 on the stack's perf-internal network: the site is http://web:8081 and
// catapult-api's perf routes http://api:8080 (never published). Env: STREAMERS, VIEWERS, DURATION, WEB, API.
//
// streamers  each signs in once (/perf/login, session cookie), opens the live streams its channel page keeps
//            open (live.js's /events/nav and channel-events.js's /events/channel/{u}: catapult has no
//            WebSocket, these SSE streams are its live connections), then every 30 s ± 5 s loads one of
//            its channel page, the SPA channel fragment or the navbar's channel list, and every
//            10 min ± 1 min plays another game (the perf game route, then the real detection cycle).
// viewers    each polls its streamer's public game route, /api/user/game/{widgetToken}, relayed by web,
//            every 15 s: no widget script in the repository sets an interval, 15 s is the plan's default.
//
// A live stream closed before the end of the run, or refused, counts in ws_unexpected_close (the plan's
// name, kept although they are SSE streams); one closed by the server at its 30 min timeout
// (SseStreams.TIMEOUT) is reopened, as EventSource does, and does not.
//
// Every request is tagged kind:request, but the streams, kind:stream: a stream's duration is how long it stayed
// open, so the latency threshold only looks at kind:request.

import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { setTimeout } from "k6/timers";

const WEB = __ENV.WEB || "http://web:8081";
const API = __ENV.API || "http://api:8080";
const STREAMERS = Number(__ENV.STREAMERS || 100);
const VIEWERS = Number(__ENV.VIEWERS || 1000);
const DURATION = __ENV.DURATION || "30m";

const PAGE_EVERY_MS = 30_000;
const PAGE_JITTER_MS = 5_000;
const GAME_EVERY_MS = 10 * 60_000;
const GAME_JITTER_MS = 60_000;
const WIDGET_EVERY_MS = 15_000;
/** EventSource's own wait before it reconnects. */
const STREAM_RETRY_MS = 3_000;
/** SseStreams.TIMEOUT: a stream that lived this long (minus a minute) was closed by the server, as planned. */
const STREAM_TIMEOUT_MS = 30 * 60_000;
/** The streams are left at this much before the scenario's end, so that every request ends within it. */
const END_MARGIN_MS = 2_000;
/** Five of PerfDataSeeder's games (bound already) and five unknown ones (a new binding each). */
const GAMES = ["Celeste", "Hades", "Hollow Knight", "Stardew Valley", "Factorio",
    "Terraria", "Dead Cells", "Slay the Spire", "Outer Wilds", "Cuphead"];

const unexpectedCloses = new Counter("ws_unexpected_close");

// One always-true threshold per request group, only so that k6 exports its submetrics (perf/report.mjs).
const GROUPS = ["login", "channel", "spa-channel", "spa-nav-channels", "game", "widget", "stream"];
const groupThresholds = Object.fromEntries(GROUPS.flatMap((group) => [
    [`http_req_duration{group:${group}}`, ["max>=0"]],
    [`http_req_failed{group:${group}}`, ["rate>=0"]],
]));

export const options = {
    setupTimeout: "60s",
    summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "count"],
    scenarios: {
        // One iteration per VU, for the whole run: exec.scenario.iterationInTest is then 0..STREAMERS-1, one
        // value per VU, which maps each streamer VU to its own account (see streamer()).
        streamers: {
            executor: "per-vu-iterations", exec: "streamer", vus: STREAMERS, iterations: 1, maxDuration: DURATION,
        },
        viewers: { executor: "constant-vus", exec: "viewer", vus: VIEWERS, duration: DURATION },
    },
    thresholds: {
        "http_req_duration{kind:request}": ["p(95)<500"],
        http_req_failed: ["rate<0.01"],
        ws_unexpected_close: ["count==0"],
        ...groupThresholds,
    },
};

/** "30m", "90s", "1h30m", "500ms" -> milliseconds. */
export function durationMs(text) {
    const units = { ms: 1, s: 1000, m: 60_000, h: 3_600_000 };
    let total = 0;
    for (const [, value, unit] of text.matchAll(/([0-9.]+)(ms|s|m|h)/g)) total += Number(value) * units[unit];
    if (!total) throw new Error(`unreadable duration: ${text}`);
    return total;
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
const jittered = (every, jitter) => every - jitter + Math.random() * 2 * jitter;
const pick = (list) => list[Math.floor(Math.random() * list.length)];

export function setup() {
    const res = http.get(`${API}/api/internal/perf/accounts`, { tags: { group: "setup", kind: "request" } });
    if (res.status !== 200) throw new Error(`GET /api/internal/perf/accounts: ${res.status}`);
    const accounts = res.json();
    if (accounts.length < STREAMERS) {
        console.warn(`${accounts.length} perf accounts for ${STREAMERS} streamers: some are shared`);
    }
    return { accounts };
}

/** When this scenario's VUs stop: its start plus DURATION, minus the margin. */
function deadline() {
    return exec.scenario.startTime + durationMs(DURATION) - END_MARGIN_MS;
}

/**
 * True once past `end`, after waiting out the scenario: an iteration started in the margin would otherwise
 * end at once, and the next one too, each streamer signing in again in a loop until k6 stops it.
 */
async function over(end) {
    if (Date.now() < end) return false;
    await wait(end + END_MARGIN_MS - Date.now() + 1_000);
    return true;
}

/**
 * Keeps `path`'s live stream open until `end`, reopened after a planned close; counts the unexpected ones.
 * The request lasts as long as the stream: its timeout is what is left of the run, its body is not kept.
 */
async function liveStream(path, end) {
    while (Date.now() < end - 1_000) {
        const opened = Date.now();
        const res = await http.asyncRequest("GET", `${WEB}${path}`, null, {
            headers: { Accept: "text/event-stream" },
            timeout: `${end - opened}ms`,
            responseType: "none",
            tags: { group: "stream", name: "stream", kind: "stream" },
            // Status 0: the request timed out at the end of the run, which is how this script closes it
            responseCallback: http.expectedStatuses(0, 200),
        });
        const closed = Date.now();
        if (closed >= end - 1_000) return; // closed by this script, at the end of the run
        if (res.status === 200 && closed - opened >= STREAM_TIMEOUT_MS - 60_000) continue; // the server's timeout
        unexpectedCloses.add(1, { stream: path.startsWith("/events/channel/") ? "channel" : "nav" });
        console.warn(`live stream ${path} closed after ${closed - opened} ms: ${res.status} ${res.error || ""}`);
        await wait(STREAM_RETRY_MS);
    }
}

/** Signs `user` in (the session cookie lands in the VU's jar); true when it worked. */
async function signIn(user) {
    const login = await http.asyncRequest("GET", `${WEB}/perf/login?username=${user}`, null, {
        redirects: 0, tags: { group: "login", name: "login", kind: "request" },
        responseCallback: http.expectedStatuses(302),
    });
    return check(login, {
        "login: 302 with a session cookie": (r) => r.status === 302 && !!r.cookies.JSESSIONID?.length,
    });
}

/**
 * One streamer for the whole run. The scenario runs exactly one iteration per VU (per-vu-iterations), so
 * exec.scenario.iterationInTest, unique per iteration, is unique per VU too and runs 0..STREAMERS-1: the
 * account it picks is this VU's alone (exec.vu.idInTest would not do: the viewers' VUs share its numbering,
 * in no set order). A failed login is retried within the iteration, never by a new one.
 */
export async function streamer(data) {
    const end = deadline();
    const account = data.accounts[exec.scenario.iterationInTest % data.accounts.length];
    const user = encodeURIComponent(account.username);

    while (!(await signIn(user))) {
        if (Date.now() + 5_000 >= end) return;
        await wait(5_000);
    }

    const streams = [liveStream("/events/nav", end), liveStream(`/events/channel/${user}`, end)];
    const pages = [
        ["channel", `/channel/${user}`],
        ["spa-channel", `/spa/channel/${user}`],
        ["spa-nav-channels", "/spa/nav-channels"],
    ];
    // The login lands on the channel page: loaded at once. The first game change comes at a random point of
    // the first interval, so that the streamers do not all change at the same minute.
    let nextPage = Date.now();
    let nextGame = Date.now() + Math.random() * jittered(GAME_EVERY_MS, GAME_JITTER_MS);
    let landed = false;
    while (true) {
        await wait(Math.min(nextPage, nextGame, end) - Date.now());
        if (Date.now() >= end) break;
        if (Date.now() >= nextPage) {
            const [group, path] = landed ? pick(pages) : pages[0];
            landed = true;
            const res = await http.asyncRequest("GET", `${WEB}${path}`, null, { tags: { group, name: group, kind: "request" } });
            check(res, { [`${group}: 200`]: (r) => r.status === 200 });
            nextPage = Date.now() + jittered(PAGE_EVERY_MS, PAGE_JITTER_MS);
        }
        if (Date.now() >= nextGame) {
            const res = await http.asyncRequest("POST",
                `${API}/api/internal/perf/users/${user}/game?name=${encodeURIComponent(pick(GAMES))}`, null,
                { tags: { group: "game", name: "game", kind: "request" } });
            if (!check(res, { "game: 204": (r) => r.status === 204 })) {
                console.warn(`game change of ${account.username}: ${res.status} ${res.error || res.body || ""}`);
            }
            nextGame = Date.now() + jittered(GAME_EVERY_MS, GAME_JITTER_MS);
        }
    }
    await Promise.all(streams);
}

/** One viewer's overlay: its streamer's game, every 15 s. */
export async function viewer(data) {
    const end = deadline();
    if (await over(end)) return;
    const account = data.accounts[(exec.vu.idInTest - 1) % data.accounts.length];
    // The overlays did not all open at the same second
    if (exec.vu.iterationInScenario === 0) await wait(Math.random() * WIDGET_EVERY_MS);
    while (Date.now() < end) {
        const started = Date.now();
        const res = await http.asyncRequest("GET", `${WEB}/api/user/game/${account.widgetToken}`, null, {
            tags: { group: "widget", name: "widget", kind: "request" },
        });
        check(res, { "widget: 200": (r) => r.status === 200 });
        await wait(Math.min(started + WIDGET_EVERY_MS, end) - Date.now());
    }
}
