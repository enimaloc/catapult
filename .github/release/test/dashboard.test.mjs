import test from "node:test";
import assert from "node:assert/strict";
import { refreshDashboard, refreshDashboardSafely, renderDashboard } from "../dashboard.mjs";

const pull = (number, over) => ({
  number,
  title: `chore(release): v1.0.0-BETA.${number}`,
  draft: true,
  autoMerge: false,
  labels: ["release"],
  base: "beta",
  head: "dev",
  ...over,
});

function fakeGitHub({ pulls = [], mergeStates = {}, releases = [], issues = [] } = {}) {
  const calls = [];
  return {
    calls,
    async listPulls() {
      return pulls;
    },
    async getMergeState(p) {
      return mergeStates[p.number] ?? "CLEAN";
    },
    async listReleases() {
      return releases;
    },
    async findIssuesByLabel(label, marker) {
      calls.push(["find", label, marker]);
      return issues;
    },
    async createIssue(fields) {
      calls.push(["create", fields]);
      return { number: 7, nodeId: "I_7", ...fields };
    },
    async closeIssue(number) {
      calls.push(["close", number]);
    },
    async updateIssue(number, fields) {
      calls.push(["update", number, fields]);
    },
    async pinIssue(created) {
      calls.push(["pin", created.nodeId]);
    },
  };
}

const releases = [{ tag: "v1.0.0-BETA.96", url: "https://example/r/96", prerelease: true }];

test("render_listsOpenPromotionsWithState", () => {
  const text = renderDashboard({
    promotions: [
      { number: 1, head: "dev", base: "beta", version: "v1.0.0-BETA.97", state: "draft" },
      { number: 2, head: "beta", base: "main", version: "v1.0.0", state: "prête (auto-merge)" },
    ],
    backports: [{ number: 3, title: "chore(release): backport v1.0.1 to beta", head: "main", base: "beta" }],
    releases,
  });
  assert.ok(text.startsWith("<!-- release-dashboard -->"));
  assert.match(text, /\| `dev` → `beta` \| v1\.0\.0-BETA\.97 \| draft \| #1 \|/);
  assert.match(text, /\| `beta` → `main` \| v1\.0\.0 \| prête \(auto-merge\) \| #2 \|/);
  assert.match(text, /- #3 `main` → `beta`/);
  assert.match(text, /\[v1\.0\.0-BETA\.96\]\(https:\/\/example\/r\/96\) \(pré-release\)/);
  assert.match(renderDashboard({ promotions: [], backports: [], releases: [] }), /Aucune promotion en cours/);
});

test("refresh_derivesStateFromThePullRequests", async () => {
  const gh = fakeGitHub({
    pulls: [
      pull(1),
      pull(2, { draft: false }),
      pull(3, { draft: false, autoMerge: true, head: "beta", base: "main", title: "chore(release): v1.0.0" }),
      pull(4, { draft: false, base: "main", head: "dev" }), // not a promotion pair
      pull(5, { labels: ["backport"], head: "main", base: "beta" }),
      pull(6, { labels: [] }),
    ],
    mergeStates: { 2: "DIRTY" },
  });
  await refreshDashboard({ gh });
  const body = gh.calls.find(([name]) => name === "create")[1].body;
  assert.match(body, /v1\.0\.0-BETA\.1 \| draft \| #1/);
  assert.match(body, /v1\.0\.0-BETA\.2 \| en conflit \| #2/);
  assert.match(body, /\| v1\.0\.0 \| prête \(auto-merge\) \| #3/);
  assert.doesNotMatch(body, /#4|#6/);
  assert.match(body, /- #5 /);
});

test("refresh_createsAndPinsWhenMissing", async () => {
  const gh = fakeGitHub({ pulls: [pull(1)], releases });
  assert.equal(await refreshDashboard({ gh }), "created");
  const create = gh.calls.find(([name]) => name === "create")[1];
  assert.equal(create.title, "🚀 Releases en cours");
  assert.deepEqual(create.labels, ["release"]);
  assert.deepEqual(gh.calls.at(-1), ["pin", "I_7"]);
});

test("refresh_updatesOnlyWhenChanged", async () => {
  const state = { pulls: [pull(1)], releases };
  const body = renderDashboard({
    promotions: [{ number: 1, head: "dev", base: "beta", version: "v1.0.0-BETA.1", state: "draft" }],
    backports: [],
    releases,
  });
  const same = fakeGitHub({ ...state, issues: [{ number: 7, nodeId: "I_7", body, pinned: true }] });
  assert.equal(await refreshDashboard({ gh: same }), "unchanged");
  assert.ok(!same.calls.some(([name]) => name === "update" || name === "create"));

  const stale = fakeGitHub({ ...state, issues: [{ number: 7, nodeId: "I_7", body: "<!-- release-dashboard -->\nold", pinned: true }] });
  assert.equal(await refreshDashboard({ gh: stale }), "updated");
  assert.deepEqual(stale.calls.at(-1), ["update", 7, { body }]);
});

test("dashboardFailure_doesNotFailTheRelease", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const result = await refreshDashboardSafely({
    gh: {},
    refresh: async () => {
      throw new Error("boom");
    },
  });
  assert.equal(result, null);
  assert.equal(warn.mock.callCount(), 1);
  assert.match(warn.mock.calls[0].arguments[0], /boom/);
});

const dashboardBody = renderDashboard({ promotions: [], backports: [], releases: [] });

test("refresh_pinsAnExistingUnpinnedDashboard", async () => {
  const gh = fakeGitHub({ issues: [{ number: 7, nodeId: "I_7", body: dashboardBody, pinned: false }] });
  assert.equal(await refreshDashboard({ gh }), "unchanged");
  assert.deepEqual(gh.calls.at(-1), ["pin", "I_7"]);
});

test("refresh_pinFailure_stillReportsTheOutcome", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const gh = fakeGitHub();
  gh.pinIssue = async () => {
    throw new Error("no pin");
  };
  assert.equal(await refreshDashboard({ gh }), "created");
  assert.match(warn.mock.calls[0].arguments[0], /^::warning::.*no pin/);
});

test("refresh_duplicates_keepsLowestClosesOthersAndPinsIt", async () => {
  const gh = fakeGitHub({
    issues: [
      { number: 5, nodeId: "I_5", body: dashboardBody, pinned: false },
      { number: 9, nodeId: "I_9", body: dashboardBody, pinned: true },
    ],
  });
  assert.equal(await refreshDashboard({ gh }), "unchanged");
  assert.deepEqual(gh.calls.filter(([n]) => n === "close" || n === "pin"), [["close", 9], ["pin", "I_5"]]);
});

test("refresh_raceAfterCreate_closesTheLaterIssue", async () => {
  const gh = fakeGitHub();
  const finds = [[], [
    { number: 6, nodeId: "I_6", body: dashboardBody, pinned: false },
    { number: 7, nodeId: "I_7", body: dashboardBody, pinned: false },
  ]];
  gh.findIssuesByLabel = async () => finds.shift();
  assert.equal(await refreshDashboard({ gh }), "unchanged");
  assert.deepEqual(gh.calls.filter(([n]) => n === "close" || n === "pin"), [["close", 7], ["pin", "I_6"]]);
});

test("refresh_ignoresLineEndingsAndTrailingWhitespace", async () => {
  const body = dashboardBody.replace(/\n/g, "\r\n") + "\r\n";
  const gh = fakeGitHub({ issues: [{ number: 7, nodeId: "I_7", body, pinned: true }] });
  assert.equal(await refreshDashboard({ gh }), "unchanged");
});

test("render_emptyBackportsAndReleases", () => {
  const text = renderDashboard({ promotions: [], backports: [], releases: [] });
  assert.match(text, /Aucun report en attente/);
  assert.match(text, /Aucune release publiée/);
});
