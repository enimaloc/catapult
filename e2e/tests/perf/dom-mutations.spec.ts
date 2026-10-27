import { expect, test, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { startCounting, stopCounting, type MutationCounts } from "./dom-mutations";
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
/** Between two live events: each one's re-render (or patch) is done before the next. */
const EVENT_GAP_MS = 2000;
/** After a user action: its request, the re-render and the live event it causes are done. */
const ACTION_SETTLE_MS = 3000;
/** The window of one user action: typing, its request and ACTION_SETTLE_MS. */
const ACTION_WINDOW_MS = 9000;

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
  const known: Result[] = existsSync(resultsFile) ? JSON.parse(readFileSync(resultsFile, "utf8")) : [];
  mkdirSync(resultsDir, { recursive: true });
  writeFileSync(
    resultsFile,
    JSON.stringify([...known.filter((r) => r.scenario !== scenario), { scenario, ...counts, budget }], null, 2),
  );
  console.log(`${scenario}: ${JSON.stringify(counts)}, budget ${budget}`);
  expect(budget, `${scenario}: no budget in dom-budgets.json (measured ${counts.nodes} nodes)`).not.toBeNull();
  expect(counts.nodes, `${scenario} nodes added and removed`).toBeLessThanOrEqual(budget as number);
}

const sum = (all: MutationCounts[]): MutationCounts => ({
  nodes: all.reduce((n, c) => n + c.nodes, 0),
  attributes: all.reduce((n, c) => n + c.attributes, 0),
  text: all.reduce((n, c) => n + c.text, 0),
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

/**
 * Sends `events` EVENT_GAP_MS apart (from the first one's start, whatever each one takes to send), then
 * waits EVENT_GAP_MS more: the window it needs is events.length * EVENT_GAP_MS.
 */
async function paced(page: Page, events: Array<() => unknown>) {
  const start = Date.now();
  for (let i = 0; i < events.length; i++) {
    await page.waitForTimeout(Math.max(0, start + i * EVENT_GAP_MS - Date.now()));
    await events[i]();
  }
  await page.waitForTimeout(Math.max(0, start + events.length * EVENT_GAP_MS - Date.now()));
}

/** Opens `url` as `username` and waits for its first render to settle. */
async function open(page: Page, username: string, url: string) {
  await loginAs(page, username);
  await page.goto(url, { waitUntil: "networkidle" });
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
    await openCustomBindingsExperiment();
    await grantInvite(STREAMER);
  });

  test("@perf dom idle-60s", async ({ page }) => {
    await open(page, STREAMER, `/channel/${STREAMER}`);
    await startCounting(page);
    judge("idle-60s", await stopCounting(page, 60_000));
  });

  test("@perf dom nav-refresh", async ({ page }) => {
    await open(page, STREAMER, `/channel/${STREAMER}`);
    await startCounting(page);
    // What the 5 min timer, a tab shown again and a reopened navbar stream do, the list unchanged
    await page.evaluate(() => (window as unknown as { CatapultSpa: { loadNavChannels(): Promise<void> } })
      .CatapultSpa.loadNavChannels());
    judge("nav-refresh", await stopCounting(page, 3000));
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
      await paced(page, events);
      judge("dock-events", await stopCounting(page, events.length * EVENT_GAP_MS + 2000));
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
      send("dtdd.mapping.changed"),
      send("dtdd.mapping.changed"),
      send("custom.bindings.updated"),
      send("custom.bindings.updated"),
      // A provider other than Steam: Xbox (off under perf), connected then disconnected again
      send("connection.changed", { provider: "XBOX", connected: true, profile: null }),
      send("connection.changed", { provider: "XBOX", connected: false, profile: null }),
    ];
    await startCounting(page);
    await paced(page, events);
    judge("channel-live-events", await stopCounting(page, events.length * EVENT_GAP_MS + 2000));
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
    await page.waitForTimeout(ACTION_SETTLE_MS);
    judge("channel-reconnect", await stopCounting(page, 12_000));
  });

  test("@perf dom custom-binding-crud", async ({ page }) => {
    await deleteCustomBindings(STREAMER); // those an interrupted run left
    await open(page, STREAMER, `/channel/${STREAMER}`);
    try {
      judge("custom-binding-crud", await customBindingCrud(page));
    } finally {
      await deleteCustomBindings(STREAMER);
    }
  });

  async function customBindingCrud(page: Page): Promise<MutationCounts> {
    const steps: MutationCounts[] = [];
    const list = page.locator("#channel-custom-bindings-list");
    const saved = () => list.locator(".custom-binding-item[data-custom-binding-id]");
    await expect(saved()).toHaveCount(0);

    // Create: "New", a name, a category picked from the search, save
    await startCounting(page);
    await page.locator("#custom-binding-new-btn").click();
    const fresh = list.locator(".custom-binding-new");
    await type(page, "#channel-custom-bindings-list .custom-binding-new mdui-text-field.custom-binding-name", "Perf DOM");
    await fresh.locator(".custom-binding-game-input").fill("Just");
    await fresh.locator(".custom-binding-game-results mdui-list-item").first().click();
    await posting(page, `/channel/${STREAMER}/custom-bindings`, () => fresh.locator(".custom-binding-save").click());
    await expect(saved()).toHaveCount(1);
    await page.waitForTimeout(ACTION_SETTLE_MS);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));

    const id = await saved().first().getAttribute("data-custom-binding-id");

    // Edit: open it, rename it, save
    await startCounting(page);
    await saved().first().locator("[slot=header]").click();
    await type(page, "#channel-custom-bindings-list .custom-binding-item[data-custom-binding-id] mdui-text-field.custom-binding-name", "Perf DOM 2");
    await posting(page, `/channel/${STREAMER}/custom-bindings/${id}`,
      () => saved().first().locator(".custom-binding-save").click());
    await expect(saved().first().locator(".custom-binding-name-label")).toHaveText("Perf DOM 2");
    await page.waitForTimeout(ACTION_SETTLE_MS);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));

    // Delete: open it, delete, confirm
    await startCounting(page);
    await saved().first().locator("[slot=header]").click();
    await saved().first().locator(".custom-binding-delete").click();
    await posting(page, `/channel/${STREAMER}/custom-bindings/${id}/delete`,
      () => page.locator("#custom-binding-delete-dialog .custom-binding-delete-confirm").click());
    await expect(saved()).toHaveCount(0);
    await page.waitForTimeout(ACTION_SETTLE_MS);
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
    await page.waitForTimeout(ACTION_SETTLE_MS);
    steps.push(await stopCounting(page, ACTION_WINDOW_MS));

    // Regenerate the OBS widget link, confirmed
    await showTab(page, "integrations");
    await expect(page.locator("#obs-widget")).toHaveCount(1);
    await startCounting(page);
    await page.locator("#obs-widget-regenerate-btn").click();
    await posting(page, `/channel/${STREAMER}/settings/twitchat/regenerate`,
      () => page.locator("#obs-widget-regenerate-dialog .obs-widget-regenerate-confirm").click());
    await page.waitForTimeout(ACTION_SETTLE_MS);
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
      await page.waitForTimeout(ACTION_SETTLE_MS);
      steps.push(await stopCounting(page, ACTION_WINDOW_MS));

      // Groups: a group created
      await showTab(page, "groups");
      await startCounting(page);
      await type(page, ".admin-groups-create mdui-text-field[name=key]", "perf_dom");
      await type(page, ".admin-groups-create mdui-text-field[name=name]", "Perf DOM");
      await posting(page, "/admin/groups", () => page.locator(".admin-groups-create-save").click());
      await page.waitForTimeout(ACTION_SETTLE_MS);
      steps.push(await stopCounting(page, ACTION_WINDOW_MS));

      // Meta: a meta tag added
      await showTab(page, "meta");
      await startCounting(page);
      await type(page, ".admin-meta-tags-form mdui-text-field[name=key]", "perf-dom");
      await type(page, ".admin-meta-tags-form mdui-text-field[name=content]", "1");
      await posting(page, "/admin/meta/tags", () => page.locator(".admin-meta-tag-save").click());
      await page.waitForTimeout(ACTION_SETTLE_MS);
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
  try {
    const page = async () => (await admin.api.get("/spa/admin/whitelist")).text();
    const whitelistOff = (await page()).includes("data-whitelist-toggle>Enable");
    if (whitelistOff) await admin.post("/admin/whitelist/toggle");
    const invitesOff = (await page()).includes("data-invites-toggle>Enable");
    if (invitesOff) await admin.post("/admin/whitelist/invite/toggle");
    expect((await member.api.get(`/spa/channel/${username}`)).ok()).toBeTruthy();
    if (invitesOff) await admin.post("/admin/whitelist/invite/toggle");
    if (whitelistOff) await admin.post("/admin/whitelist/toggle");
  } finally {
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
