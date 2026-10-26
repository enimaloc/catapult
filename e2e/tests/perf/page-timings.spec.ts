import { expect, request, test, type Page } from "@playwright/test";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { measureNavigation, measureTab } from "./vitals";

/**
 * Page timings under the perf profile (perf/run.sh --pages-only): 1 warm-up then 20 measured runs
 * per page and tab, judged on the p95. Run by hand before a release, never in CI.
 */

const WARM_UP = 1;
const RUNS = 20;
const BUDGETS = { lcp: 2500, ttfb: 800, tab: 500, inp: 200 };

interface Result {
  name: string;
  metric: string;
  samples: number[];
  p50: number;
  p95: number;
  budget: number;
  note?: string;
}

const resultsDir = process.env.PERF_RESULTS_DIR || join(__dirname, "..", "..", "perf-results");
const resultsFile = join(resultsDir, "pages.json");

/** Nearest-rank percentile. */
function percentile(samples: number[], p: number): number {
  const sorted = [...samples].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)];
}

function record(name: string, metric: string, samples: number[], budget: number): Result {
  const round = (n: number) => Math.round(n * 10) / 10;
  const result: Result = {
    name,
    metric,
    samples: samples.map(round),
    p50: round(percentile(samples, 50)),
    p95: round(percentile(samples, 95)),
    budget,
  };
  if (metric === "inp" && samples.every((sample) => sample === 0)) {
    result.note = "below the 16 ms Event Timing threshold: no entry reported, not a measured 0 ms";
  }
  // Rewritten at each record: a failed test restarts the worker, which must not lose the earlier ones.
  const known: Result[] = existsSync(resultsFile) ? JSON.parse(readFileSync(resultsFile, "utf8")) : [];
  mkdirSync(resultsDir, { recursive: true });
  writeFileSync(
    resultsFile,
    JSON.stringify([...known.filter((r) => r.name !== name || r.metric !== metric), result], null, 2),
  );
  return result;
}

async function loginAs(page: Page, username: string) {
  const response = await page.goto(`/perf/login?username=${username}`);
  expect(response?.ok()).toBeTruthy();
}

/**
 * Opens the custom bindings experiment to every member, as an admin would from the admin screen: it is
 * closed by default, which hides the API token card (and the /me/api-token request) from the channel page.
 */
async function openCustomBindingsExperiment() {
  const api = await request.newContext({ baseURL: process.env.PERF_BASE_URL });
  try {
    expect((await api.get("/perf/login?username=perf-admin")).ok()).toBeTruthy();
    expect((await api.get("/admin")).ok()).toBeTruthy();
    const xsrf = (await api.storageState()).cookies.find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
    const post = async (path: string, data: object = {}) => {
      const response = await api.post(path, { data, headers: { "X-XSRF-TOKEN": xsrf } });
      expect(response.ok(), `POST ${path}: ${response.status()}`).toBeTruthy();
    };
    const list = await (await api.get("/spa/admin/experiments")).text();
    const row = list.split('data-admin-open="').find((chunk) => chunk.includes(">custom.bindings<"));
    const id = row?.slice(0, 36);
    expect(id, "custom.bindings experiment in the admin list").toBeTruthy();
    const detail = await (await api.get(`/spa/admin/experiments/${id}`)).text();
    // Already open (the worker restarts after a failed test and runs this again): activating again answers 409.
    if (/data-status="ACTIVE"/.test(detail)) return;
    const weights: Record<string, number> = {};
    for (const [, variant, key] of detail.matchAll(/data-variant="([0-9a-f-]{36})"[^>]*data-variant-key="([^"]+)"/g)) {
      weights[variant] = key === "control" ? 0 : 1;
    }
    expect(Object.keys(weights).length).toBeGreaterThan(1);
    await post(`/admin/experiments/${id}/weights`, weights);
    await post(`/admin/experiments/${id}/rules`, { type: "RANDOM", percentage: 100 });
    await post(`/admin/experiments/${id}/activate`);
  } finally {
    await api.dispose();
  }
}

function pageTest(user: string, name: string, url: string) {
  test(`@perf ${name}`, async ({ page }) => {
    await loginAs(page, user);
    const ttfb: number[] = [];
    const lcp: number[] = [];
    for (let i = 0; i < WARM_UP + RUNS; i++) {
      const timing = await measureNavigation(page, url);
      if (i >= WARM_UP) {
        ttfb.push(timing.ttfb);
        lcp.push(timing.lcp);
      }
    }
    const measured = [record(name, "ttfb", ttfb, BUDGETS.ttfb), record(name, "lcp", lcp, BUDGETS.lcp)];
    for (const r of measured) expect(r.p95, `${name} ${r.metric} p95`).toBeLessThanOrEqual(r.budget);
  });
}

/**
 * A tab is measured on a freshly loaded page each time (admin tabs fetch their content once per
 * page). The page's default tab has nothing to switch from, so another tab is opened first.
 */
function tabTest(user: string, name: string, pageUrl: string, tabs: string, tab: string, otherTab: string) {
  test(`@perf ${name}`, async ({ page }) => {
    await loginAs(page, user);
    const tabSelector = (value: string) => `mdui-tabs#${tabs} mdui-tab[value="${value}"]`;
    const panelSelector = (value: string) => `mdui-tabs#${tabs} mdui-tab-panel[value="${value}"]`;
    const defaultTab = tab === (tabs === "admin-tabs" ? "members" : "overview");
    const switchMs: number[] = [];
    const inp: number[] = [];
    for (let i = 0; i < WARM_UP + RUNS; i++) {
      await page.goto(pageUrl, { waitUntil: "load" });
      await expect(page.locator(tabSelector(tab)), `${tab} tab for ${user}`).toHaveCount(1);
      if (defaultTab) await measureTab(page, tabSelector(otherTab), panelSelector(otherTab));
      const timing = await measureTab(page, tabSelector(tab), panelSelector(tab));
      if (i >= WARM_UP) {
        switchMs.push(timing.switchMs);
        inp.push(timing.inp);
      }
    }
    const measured = [record(name, "tab-switch", switchMs, BUDGETS.tab), record(name, "inp", inp, BUDGETS.inp)];
    for (const r of measured) expect(r.p95, `${name} ${r.metric} p95`).toBeLessThanOrEqual(r.budget);
  });
}

test.describe("streamer", () => {
  test.beforeAll(openCustomBindingsExperiment);

  pageTest("perf-streamer-001", "home", "/");
  pageTest("perf-streamer-001", "channel", "/channel/perf-streamer-001");
  for (const tab of ["overview", "settings", "integrations", "commands", "invitations", "account"]) {
    tabTest(
      "perf-streamer-001",
      `channel tab ${tab}`,
      "/channel/perf-streamer-001",
      "channel-tabs",
      tab,
      tab === "settings" ? "account" : "settings",
    );
  }

  test("@perf api token status is fetched once per channel page", async ({ page }) => {
    await loginAs(page, "perf-streamer-001");
    const tokenRequests: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).pathname === "/me/api-token") tokenRequests.push(request.url());
    });
    await page.goto("/channel/perf-streamer-001", { waitUntil: "networkidle" });
    // A re-render of the page (live events, a saved setting) must reuse the status it already has.
    for (let i = 0; i < 2; i++) {
      await page.evaluate(() => (window as unknown as { CatapultChannel: { refresh(): Promise<void> } }).CatapultChannel.refresh());
      await page.waitForLoadState("networkidle");
    }
    expect(tokenRequests).toHaveLength(1);
  });
});

test.describe("admin", () => {
  pageTest("perf-admin", "admin", "/admin");
  tabTest("perf-admin", "admin tab members", "/admin", "admin-tabs", "members", "experiments");
  tabTest("perf-admin", "admin tab experiments", "/admin", "admin-tabs", "experiments", "members");
});
