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

function fakeGitHub({ pulls = [], mergeStates = {}, releases = [], issue = null } = {}) {
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
    async findIssueByLabel(label, marker) {
      calls.push(["find", label, marker]);
      return issue;
    },
    async createIssue(fields) {
      calls.push(["create", fields]);
      return { number: 7, nodeId: "I_7", ...fields };
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
  const same = fakeGitHub({ ...state, issue: { number: 7, nodeId: "I_7", body } });
  assert.equal(await refreshDashboard({ gh: same }), "unchanged");
  assert.ok(!same.calls.some(([name]) => name === "update" || name === "create"));

  const stale = fakeGitHub({ ...state, issue: { number: 7, nodeId: "I_7", body: "<!-- release-dashboard -->\nold" } });
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
