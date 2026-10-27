import { expect, test, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import {
  liveEvents, startCounting, stopCounting, trackLiveEvents, waitForLiveEvent, waitForQuiet, type MutationCounts,
} from "./dom-mutations";
import { loginAs, openCustomBindingsExperiment, signIn, type Member } from "./session";

/**
 * DOM mutations per scenario under the perf profile (perf/run.sh --pages-only or --dom-only): the nodes
 * added and removed, attributes and texts changed while a scenario runs (dom-mutations.ts). Each scenario
 * fails when its nodes exceed its budget in dom-budgets.json, the count measured on the code it was set on;
 * attributes and texts are reported, never judged. Run by hand, never in CI.
 *
 * The scenarios run as perf-streamer-002, so that what they change (a bot toggled, an invite created) never
 * reaches the page timings of perf-streamer-001. Live events come from catapult-api itself when a web action
 * makes them (another device of the streamer's, signed in apart), else are published on the Redis channel
 * catapult-web relays (ChannelEventRedisBridge), in the envelope and payloads catapult-api publishes there.
 * Every scenario leaves the state as it found it.
 */

const STREAMER = "perf-streamer-002";
const ADMIN = "perf-admin";
/**
 * The windows of the scenarios' steps: what a step does, then until the page is quiet (waitForQuiet).
 * Fixed, and failing when overrun, so that every run counts over the same length of time.
 */
const ACTION_WINDOW_MS = 18_000;
const EVENTS_WINDOW_MS = 80_000;
/**
 * custom-binding-crud: how much later the channel's fragment answers. A save shows its result from its own
 * answer and its live event (CustomBindingsUpdatedEvent) then changes nothing: neither fetches the fragment.
 * Kept so that a re-render coming back (one the save and its event would both make) stays deterministic:
 * delayed, the two would always overlap and spa.js apply only the later one.
 */
const FRAGMENT_DELAY_MS = 1000;

const budgetsFile = join(__dirname, "dom-budgets.json");
const resultsDir = process.env.PERF_RESULTS_DIR || join(__dirname, "..", "..", "perf-results");
const resultsFile = join(resultsDir, "dom-mutations.json");
const COMPOSE = ["compose", "-p", "catapult-verify-perf", "-f", resolve(__dirname, "..", "..", "..", "perf", "compose.yml")];

interface Result extends MutationCounts {
  scenario: string;
  budget: number | null;
}

/** Writes the scenario's counts to dom-mutations.json, then judges its nodes on its budget. */
function judge(scenario: string, counts: MutationCounts) {
  const budgets: Record<string, { nodes: number }> = JSON.parse(readFileSync(budgetsFile, "utf8"));
  const budget = budgets[scenario]?.nodes ?? null;
  // Rewritten at each record: a failed test restarts the worker, which must not lose the earlier ones.
  const { trace, ...measured } = counts;
  const known: Result[] = existsSync(resultsFile) ? JSON.parse(readFileSync(resultsFile, "utf8")) : [];
  mkdirSync(resultsDir, { recursive: true });
  writeFileSync(
    resultsFile,
    JSON.stringify([...known.filter((r) => r.scenario !== scenario), { scenario, ...measured, budget }], null, 2),
  );
  if (trace) writeFileSync(join(resultsDir, `dom-trace-${scenario}.txt`), `${trace.join("\n")}\n`);
  console.log(`${scenario}: ${JSON.stringify(measured)}, budget ${budget}`);
  expect(budget, `${scenario}: no budget in dom-budgets.json (measured ${counts.nodes} nodes)`).not.toBeNull();
  expect(counts.nodes, `${scenario} nodes added and removed`).toBeLessThanOrEqual(budget as number);
}

const sum = (all: MutationCounts[]): MutationCounts => ({
  nodes: all.reduce((n, c) => n + c.nodes, 0),
  attributes: all.reduce((n, c) => n + c.attributes, 0),
  text: all.reduce((n, c) => n + c.text, 0),
  ...(all.some((c) => c.trace) ? { trace: all.flatMap((c, i) => [`-- step ${i + 1}`, ...(c.trace ?? [])]) } : {}),
});

function docker(args: string[]): string {
  return execFileSync("docker", [...COMPOSE, ...args], { encoding: "utf8", timeout: 30_000 });
}

/** The account id of `username`, from catapult-api's perf routes (inside the stack's network). */
function accountId(username: string): string {
  const accounts: { username: string; userId: string }[] = JSON.parse(
    docker(["exec", "-T", "api", "wget", "-q", "-O-", "http://localhost:8080/api/internal/perf/accounts"]),
  );
  const id = accounts.find((a) => a.username === username)?.userId;
  if (!id) throw new Error(`no perf account ${username}`);
  return id;
}

/** Publishes the channel event `name` of `login` (owner `ownerId`) as catapult-api does. */
function publish(ownerId: string, login: string, name: string, data: object = {}) {
  const envelope = { name, data: { channelId: ownerId, channelLogin: login, ...data }, ts: Date.now() };
  docker(["exec", "-T", "redis", "redis-cli", "PUBLISH", `catapult:events:channel:${ownerId}`, JSON.stringify(envelope)]);
}

interface Binding {
  id: string;
  sourceName: string;
  sourceType: string;
  /** Whether it is the binding of the game played now */
  current: boolean;
}

/** The channel's game bindings, from its page as the server renders it. */
async function bindings(page: Page, login: string): Promise<Binding[]> {
  return page.evaluate(async (login) => {
    const html = await (await fetch(`/spa/channel/${login}`)).text();
    const doc = new DOMParser().parseFromString(html, "text/html");
    return [...doc.querySelectorAll("#channel-bindings-list [data-binding-id]")].map((row) => ({
      id: (row as HTMLElement).dataset.bindingId as string,
      sourceName: row.querySelector(".binding-game-name")?.textContent?.trim() ?? "",
      sourceType: row.querySelector(".binding-platform-chips")?.textContent?.trim() ?? "",
      current: row.classList.contains("is-open"),
    }));
  }, login);
}

/** The game.detected payload of `binding`. */
const detected = ({ id, sourceName, sourceType }: Binding) => ({ bindingId: id, sourceName, sourceType });

/** How long channel-obs-widget.js waits for OBS before its check says it is unreachable (CONNECT_WAIT_MS). */
const OBS_CHECK_MS = 5000;

/** Whether the OBS card's source check (if the page has the card and checks) is over: no longer "checking". */
const obsChecked = () => {
  const status = document.getElementById("obs-widget-source-status");
  return !status || status.textContent !== status.dataset.labelChecking;
};

/**
 * Waits for the page to be quiet (waitForQuiet), including the OBS card's source check of a render: without
 * OBS, it shows "unreachable" OBS_CHECK_MS after it starts, which a quiet period alone does not wait for.
 */
async function settled(page: Page) {
  await waitForQuiet(page);
  if (await page.evaluate(obsChecked)) return;
  await page.waitForFunction(obsChecked, undefined, { timeout: OBS_CHECK_MS * 3 });
  await waitForQuiet(page);
}

/**
 * Runs each step in turn, each followed by the live event it causes (when `live`) and by the page settling
 * down (settled).
 */
async function oneByOne(page: Page, steps: Array<() => unknown>, live = true) {
  for (const step of steps) {
    const seen = await liveEvents(page);
    await step();
    if (live) await waitForLiveEvent(page, seen);
    await settled(page);
  }
}

/**
 * Opens `url` as `username`, its live events tracked (trackLiveEvents), and waits for its first render to
 * settle. Unless `clocks`, the page's Date.now() is frozen at its load (what the clocks of time.js read): the
 * clocks, the subject of idle-60s, would otherwise change text whenever a minute turns, which a window may
 * or may not hold. Only Date.now(): the page's timers and new Date() are left alone.
 */
async function open(page: Page, username: string, url: string, { clocks = false } = {}) {
  if (!clocks) {
    await page.addInitScript(() => {
      const loaded = Date.now();
      Date.now = () => loaded;
    });
  }
  await trackLiveEvents(page);
  await loginAs(page, username);
  await page.goto(url, { waitUntil: "networkidle" });
  await page.waitForFunction(obsChecked, undefined, { timeout: OBS_CHECK_MS * 3 });
}

/** Clicks the tab `value` of the page's tabs and waits for its requests. */
async function showTab(page: Page, value: string) {
  await page.locator(`mdui-tab[value="${value}"]`).click();
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(500);
}

/** Runs `action` (a click that posts) and expects its POST to `path` to succeed. */
async function posting(page: Page, path: string, action: () => Promise<unknown>) {
  const answered = page.waitForResponse((r) => r.request().method() === "POST" && new URL(r.url()).pathname === path);
  await action();
  const response = await answered;
  expect(response.ok(), `POST ${path}: ${response.status()}`).toBeTruthy();
}

/** Types `text` into the field of the mdui-text-field `field` (its input sits in its shadow root). */
async function type(page: Page, field: string, text: string) {
  const input = page.locator(`${field} input`).first();
  await input.fill(text);
}

test.skip(!process.env.PERF_BASE_URL, "perf/run.sh only: needs a perf stack (PERF_BASE_URL)");

test.describe("DOM mutations", () => {
  // Both change the channel page every scenario renders: done first, whatever the order the scenarios run in.
  test.beforeAll(async () => {
    // A new run starts a new file; a worker restarted after a failed test (index > 0) adds to it.
    if (test.info().workerIndex === 0) rmSync(resultsFile, { force: true });
    await openCustomBindingsExperiment();
    await grantInvite(STREAMER);
  });

  test("@perf dom idle-60s", async ({ page }) => {
    // The clocks are the notifications' ages, seeded at the stack's start: a few seconds or minutes old
    // depending on how fast it came up, so they change every second or once a minute. The page's Date.now()
    // runs on from 10 min after the newest of them: each one then changes once in the 60 s, half way.
    await loginAs(page, STREAMER);
    const menu = await (await page.request.get("/spa/notifications/menu")).text();
    const created = [...menu.matchAll(/data-live-time="relative"[^>]*datetime="([^"]+)"|datetime="([^"]+)"[^>]*data-live-time="relative"/g)]
      .map(([, a, b]) => Date.parse(a ?? b)).filter(Number.isFinite);
    expect(created.length, "notifications with an age in the menu").toBeGreaterThan(0);
    const offset = Math.max(...created) + 10 * 60_000 - Date.now();
    await page.addInitScript((offset) => {
      const now = Date.now;
      Date.now = () => now() + offset;
    }, offset);
    await open(page, STREAMER, `/channel/${STREAMER}`, { clocks: true });
    await startCounting(page);
    judge("idle-60s", await stopCounting(page, 60_000));
  });

  test("@perf dom nav-refresh", async ({ page }) => {
    await open(page, STREAMER, `/channel/${STREAMER}`);
    await startCounting(page);
    // What the 5 min timer, a tab shown again and a reopened navbar stream do, the list unchanged
    await page.evaluate(() => (window as unknown as { CatapultSpa: { loadNavChannels(): Promise<void> } })
      .CatapultSpa.loadNavChannels());
    await settled(page);
    judge("nav-refresh", await stopCounting(page, 5000));
  });

  test("@perf dom dock-events", async ({ page }) => {
    const ownerId = accountId(STREAMER);
    await open(page, STREAMER, `/channel/${STREAMER}/dock`);
    const games = await bindings(page, STREAMER);
    const dock = page.locator(".obs-dock-binding");
    const current = await dock.getAttribute("data-binding-id");
    const playing = games.find((g) => g.id === current);
    const other = games.find((g) => g.id !== current);
    expect(playing && other, "two bindings, one of them played").toBeTruthy();
    const blocked = (await page.locator("#obs-dock-ccl-enabled").getAttribute("data-blocked")) || "";
    const blockedCcls = blocked ? blocked.split(",") : [];
    const device = await signIn(STREAMER, `/channel/${STREAMER}`);
    try {
      const base = `/channel/${STREAMER}`;
      const events = [
        () => publish(ownerId, STREAMER, "game.detected", detected(other!)),
        () => publish(ownerId, STREAMER, "game.detected", detected(playing!)),
        () => device.post(`${base}/settings/bot`),
        () => device.post(`${base}/settings/bot`),
        () => device.post(`${base}/settings/ccl`, { cclEnabled: false, blockedCcls }),
        () => device.post(`${base}/settings/ccl`, { cclEnabled: true, blockedCcls }),
        () => device.post(`${base}/bindings/${playing!.id}/ccl-toggle`, { enabled: false }),
        () => device.post(`${base}/bindings/${playing!.id}/ccl-toggle`, { enabled: true }),
        () => device.post(`/channel/bindings/${playing!.id}/tw-enabled`, { enabled: false }),
        () => device.post(`/channel/bindings/${playing!.id}/tw-enabled`, { enabled: true }),
      ];
      await startCounting(page);
      await oneByOne(page, events);
      judge("dock-events", await stopCounting(page, EVENTS_WINDOW_MS));
    } finally {
      await device.dispose();
    }
  });

  test("@perf dom channel-live-events", async ({ page }) => {
    const ownerId = accountId(STREAMER);
    await open(page, STREAMER, `/channel/${STREAMER}`);
    const games = await bindings(page, STREAMER);
    const playing = games.find((g) => g.current);
    const other = games.find((g) => !g.current);
    expect(playing && other, "two bindings, one of them played").toBeTruthy();
    const send = (name: string, data: object = {}) => () => publish(ownerId, STREAMER, name, data);
    const events = [
      send("game.detected", detected(other!)),
      send("game.detected", detected(playing!)),
      send("bot.toggled", { enabled: false }),
      send("bot.toggled", { enabled: true }),
      // The mapping of a game the page shows no card for (IGDB is off under perf): the page re-renders
      send("dtdd.mapping.changed", { igdbId: "1", current: null, hasPending: false }),
      send("dtdd.mapping.changed", { igdbId: "1", current: null, hasPending: false }),
      // The streamer has no custom binding (custom-binding-crud leaves none)
      send("custom.bindings.updated", { bindings: [], activeId: null }),
      send("custom.bindings.updated", { bindings: [], activeId: null }),
      // A provider other than Steam: Xbox (off under perf), connected then disconnected again
      send("connection.changed", { provider: "XBOX", connected: true, profile: null }),
      send("connection.changed", { provider: "XBOX", connected: false, profile: null }),
    ];
    await startCounting(page);
    await oneByOne(page, events);
    judge("channel-live-events", await stopCounting(page, EVENTS_WINDOW_MS));
  });

  test("@perf dom channel-reconnect", async ({ page }) => {
    // The page's EventSources, so that the test can end the channel's stream
    await page.addInitScript(() => {
      const Native = window.EventSource;
      const sources: EventSource[] = [];
      (window as unknown as { __eventSources: EventSource[] }).__eventSources = sources;
      window.EventSource = class extends Native {
        constructor(url: string | URL, init?: EventSourceInit) {
          super(url, init);
          sources.push(this);
        }
      };
    });
    await open(page, STREAMER, `/channel/${STREAMER}`);
    await startCounting(page);
    // The channel's stream ends for good, as when its reconnection is answered by a maintenance page:
    // channel-events.js opens it again after its first backoff (5 s), then catches up on what it missed.
    await page.evaluate(() => {
      const sources = (window as unknown as { __eventSources: EventSource[] }).__eventSources;
      const channel = sources.find((s) => s.url.includes("/events/channel/") && s.readyState !== EventSource.CLOSED);
      if (!channel) throw new Error("no open channel stream");
      channel.close();
      channel.dispatchEvent(new Event("error"));
    });
    await expect.poll(() => page.evaluate(() => (window as unknown as { __eventSources: EventSource[] }).__eventSources
      .filter((s) => s.url.includes("/events/channel/") && s.readyState === EventSource.OPEN).length), { timeout: 10_000 })
      .toBe(1);
    await settled(page);
    judge("channel-reconnect", await stopCounting(page, ACTION_WINDOW_MS));
  });

  test("@perf dom custom-binding-crud", async ({ page }) => {
    await deleteCustomBindings(STREAMER); // those an interrupted run left
    await open(page, STREAMER, `/channel/${STREAMER}`);
    await page.route("**/spa/channel/**", async (route) => {
      await new Promise((resolve) => setTimeout(resolve, FRAGMENT_DELAY_MS));
      await route.continue();
    });
    try {
      judge("custom-binding-crud", await customBindingCrud(page));
    } finally {
      await page.unrouteAll({ behavior: "ignoreErrors" });
      await deleteCustomBindings(STREAMER);
    }
  });

  async function customBindingCrud(page: Page): Promise<MutationCounts> {
    const steps: MutationCounts[] = [];
    const list = page.locator("#channel-custom-bindings-list");
    const saved = () => list.locator(".custom-binding-item[data-custom-binding-id]");
    await expect(saved()).toHaveCount(0);

    // Each step: its save shows its result in place, then its live event finds it already shown
    const settle = async (seen: number) => {
      await waitForLiveEvent(page, seen);
      await settled(page);
    };

    // Create: "New", a name, a category picked from the search, save
    await startCounting(page);
    let seen = await liveEvents(page);
    await page.locator("#custom-binding-new-btn").click();
    const fresh = list.locator(".custom-binding-new");
    await type(page, "#channel-custom-bindings-list .custom-binding-new mdui-text-field.custom-binding-name", "Perf DOM");
    await fresh.locator(".custom-binding-game-input").fill("Just");
    await fresh.locator(".custom-binding-game-results mdui-list-item").first().click();
    await posting(page, `/channel/${STREAMER}/custom-bindings`, () => fresh.locator(".custom-binding-save").click());
    await expect(saved()).toHaveCount(1);
    await settle(seen);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));

    const id = await saved().first().getAttribute("data-custom-binding-id");

    // Edit: open it, rename it, save
    await startCounting(page);
    seen = await liveEvents(page);
    await saved().first().locator("[slot=header]").click();
    await type(page, "#channel-custom-bindings-list .custom-binding-item[data-custom-binding-id] mdui-text-field.custom-binding-name", "Perf DOM 2");
    await posting(page, `/channel/${STREAMER}/custom-bindings/${id}`,
      () => saved().first().locator(".custom-binding-save").click());
    await expect(saved().first().locator(".custom-binding-name-label")).toHaveText("Perf DOM 2");
    await settle(seen);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));

    // Delete: open it (the edit left it open), delete, confirm
    await startCounting(page);
    seen = await liveEvents(page);
    if (!(await saved().first().evaluate((row) => row.classList.contains("is-open")))) {
      await saved().first().locator("[slot=header]").click();
    }
    await saved().first().locator(".custom-binding-delete").click();
    await posting(page, `/channel/${STREAMER}/custom-bindings/${id}/delete`,
      () => page.locator("#custom-binding-delete-dialog .custom-binding-delete-confirm").click());
    await expect(saved()).toHaveCount(0);
    await settle(seen);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));
    return sum(steps);
  }

  test("@perf dom channel-actions", async ({ page }) => {
    await open(page, STREAMER, `/channel/${STREAMER}#invitations`);
    const steps: MutationCounts[] = [];

    // Regenerate the invite code, confirmed
    await expect(page.locator("#channel-invite")).toHaveCount(1);
    await startCounting(page);
    await page.locator("#invite-regenerate-btn").click();
    await posting(page, "/invite/regenerate",
      () => page.locator("#invite-regenerate-dialog .invite-regenerate-confirm").click());
    await settled(page);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));

    // Regenerate the OBS widget link, confirmed
    await showTab(page, "integrations");
    await expect(page.locator("#obs-widget")).toHaveCount(1);
    await startCounting(page);
    await page.locator("#obs-widget-regenerate-btn").click();
    await posting(page, `/channel/${STREAMER}/settings/twitchat/regenerate`,
      () => page.locator("#obs-widget-regenerate-dialog .obs-widget-regenerate-confirm").click());
    await settled(page);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));

    judge("channel-actions", sum(steps));
  });

  test("@perf dom admin-edit", async ({ page }) => {
    const admin = await signIn(ADMIN, "/admin");
    const memberHtml = await (await admin.api.get(`/spa/admin/members?q=${STREAMER}`)).text();
    const memberId = new RegExp(`data-member-id="([0-9a-f-]{36})" data-member-name="${STREAMER}"`).exec(memberHtml)?.[1];
    expect(memberId, `${STREAMER} in the admin members`).toBeTruthy();
    const steps: MutationCounts[] = [];
    try {
      // Members: a flag added on a member's targeting
      await open(page, ADMIN, `/admin#members/${memberId}`);
      const targeting = page.locator(".admin-targeting .admin-targeting-form").first();
      await expect(targeting).toBeVisible();
      await startCounting(page);
      await type(page, ".admin-targeting-form mdui-text-field[name=key]", "perf-dom");
      await type(page, ".admin-targeting-form mdui-text-field[name=value]", "1");
      await posting(page, `/admin/members/${memberId}/flags`, () => targeting.locator(".admin-targeting-flag-save").click());
      await settled(page);
      steps.push(await stopCounting(page, ACTION_WINDOW_MS));

      // Groups: a group created
      await showTab(page, "groups");
      await startCounting(page);
      await type(page, ".admin-groups-create mdui-text-field[name=key]", "perf_dom");
      await type(page, ".admin-groups-create mdui-text-field[name=name]", "Perf DOM");
      await posting(page, "/admin/groups", () => page.locator(".admin-groups-create-save").click());
      await settled(page);
      steps.push(await stopCounting(page, ACTION_WINDOW_MS));

      // Meta: a meta tag added
      await showTab(page, "meta");
      await startCounting(page);
      await type(page, ".admin-meta-tags-form mdui-text-field[name=key]", "perf-dom");
      await type(page, ".admin-meta-tags-form mdui-text-field[name=content]", "1");
      await posting(page, "/admin/meta/tags", () => page.locator(".admin-meta-tag-save").click());
      await settled(page);
      steps.push(await stopCounting(page, ACTION_WINDOW_MS));
    } finally {
      await undoAdminEdits(admin, memberId!);
      await admin.dispose();
    }
    judge("admin-edit", sum(steps));
  });
});

/**
 * Gives `username` an invite: catapult-api creates one on the invitations page while invitations and the
 * whitelist are both on. Both are put back off after, the invite stays.
 */
async function grantInvite(username: string) {
  const admin = await signIn(ADMIN, "/admin");
  const member = await signIn(username, `/channel/${username}`);
  let whitelistOn = false;
  let invitesOn = false;
  try {
    const page = async () => (await admin.api.get("/spa/admin/whitelist")).text();
    if ((await page()).includes("data-whitelist-toggle>Enable")) {
      await admin.post("/admin/whitelist/toggle");
      whitelistOn = true;
    }
    if ((await page()).includes("data-invites-toggle>Enable")) {
      await admin.post("/admin/whitelist/invite/toggle");
      invitesOn = true;
    }
    expect((await member.api.get(`/spa/channel/${username}`)).ok()).toBeTruthy();
  } finally {
    // Put back as found, whatever failed
    if (invitesOn) await admin.post("/admin/whitelist/invite/toggle");
    if (whitelistOn) await admin.post("/admin/whitelist/toggle");
    await admin.dispose();
    await member.dispose();
  }
}

/** Deletes every custom binding of `username`'s channel. */
async function deleteCustomBindings(username: string) {
  const member = await signIn(username, `/channel/${username}`);
  try {
    const page = await (await member.api.get(`/spa/channel/${username}`)).text();
    for (const [, id] of page.matchAll(/data-custom-binding-id="([^"]+)"/g)) {
      await member.post(`/channel/${username}/custom-bindings/${id}/delete`);
    }
  } finally {
    await member.dispose();
  }
}

/** Removes what admin-edit added (each only when it is there). */
async function undoAdminEdits(admin: Member, memberId: string) {
  const targeting = await (await admin.api.get(`/spa/admin/members/${memberId}`)).text();
  if (targeting.includes("perf-dom")) await admin.post(`/admin/members/${memberId}/flags/delete`, { key: "perf-dom" });
  const groups = await (await admin.api.get("/spa/admin/groups")).text();
  const group = /data-id="([0-9a-f-]{36})">\s*<span[^>]*>perf_dom</.exec(groups)?.[1];
  if (group) await admin.post(`/admin/groups/${group}/delete`);
  const meta = await (await admin.api.get("/spa/admin/meta")).text();
  for (const [row] of meta.matchAll(/<[^>]*admin-meta-tag-row[^>]*>/g)) {
    const id = /data-id="([^"]+)"/.exec(row)?.[1];
    if (id && row.includes('data-key="perf-dom"')) await admin.post(`/admin/meta/tags/${id}/delete`);
  }
}
