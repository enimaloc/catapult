import test from "node:test";
import assert from "node:assert/strict";
import { publish } from "../publish.mjs";
import { buildBody } from "../lib/pr-body.mjs";

const OWNER = "enimaloc";
const REPO = "catapult";
const sha = (c) => c.repeat(40);
const CHANGELOG = "### Fonctionnalités\n\n- abc1234 edited by hand";

// In-memory GitHub: tags map name -> commit SHA, pulls the open ones.
function fakeGitHub({ tags = {}, pulls = [], compare = {} } = {}) {
  const calls = [];
  const record = (name, ...args) => calls.push([name, ...args]);
  return {
    calls,
    async getTag(name) {
      record("getTag", name);
      return tags[name] ?? null;
    },
    async createTag(tag) {
      record("createTag", tag);
      tags[tag.tag] = tag.sha;
    },
    async listPulls({ base, head }) {
      record("listPulls", base, head);
      return pulls.filter((p) => p.base === base && (!head || p.head === head));
    },
    async compareCommits(base, head) {
      record("compareCommits", base, head);
      return compare[`${base}...${head}`] ?? [];
    },
    async createPull(fields) {
      record("createPull", fields);
      return { number: 100 + calls.length, ...fields };
    },
    async comment(number, text) {
      record("comment", number, text);
    },
  };
}

function mergedPull({
  base = "beta",
  head = "dev",
  title = "chore(release): v1.0.0-BETA.97",
  body = buildBody({ changelog: CHANGELOG, botEnabled: true, seen: [], promotedHead: sha("a") }),
  merged = true,
  headRepo = `${OWNER}/${REPO}`,
} = {}) {
  return { number: 7, title, body, state: "closed", merged, base, head, headRepo, mergeCommitSha: sha("m") };
}

// promote.mjs stand-in: records the refreshes publish.mjs asks for, in the call order.
const fakePromote = (gh) => async ({ source, target, appSlug }) => gh.calls.push(["promote", { source, target, appSlug }]);
const run = (gh, pr, promote = fakePromote(gh)) =>
  publish({ gh, pr, owner: OWNER, repo: REPO, appSlug: "catapult-release", promote });
const named = (gh, name) => gh.calls.filter((c) => c[0] === name);
const commit = (c, subject) => ({ sha: sha(c), subject, message: subject, author: OWNER, parents: [sha("0")] });

test("merge_tagsMergeCommit_withNotesFromBody", async () => {
  const gh = fakeGitHub();
  const result = await run(gh, mergedPull());
  assert.deepEqual(result, { tag: "v1.0.0-BETA.97", backports: [] });
  assert.deepEqual(named(gh, "createTag"), [
    ["createTag", { tag: "v1.0.0-BETA.97", sha: sha("m"), message: "chore(release): v1.0.0-BETA.97" }],
  ]);
  assert.deepEqual(named(gh, "createPull"), []);
});

test("stableMerge_tagsTheStableVersion", async () => {
  const gh = fakeGitHub();
  const result = await run(gh, mergedPull({ base: "main", head: "beta", title: "chore(release): v1.0.0" }));
  assert.equal(result.tag, "v1.0.0");
  assert.deepEqual(named(gh, "createPull"), [], "only a hotfix opens backports");
});

test("existingTagOnMergeCommit_isKept", async () => {
  const gh = fakeGitHub({ tags: { "v1.0.0-BETA.97": sha("m") } });
  const result = await run(gh, mergedPull());
  assert.equal(result.tag, "v1.0.0-BETA.97");
  assert.deepEqual(named(gh, "createTag"), []);
});

test("existingTagOnAnotherCommit_failsLoudly", async () => {
  const gh = fakeGitHub({ tags: { "v1.0.0-BETA.97": sha("x") } });
  await assert.rejects(run(gh, mergedPull()), /v1\.0\.0-BETA\.97.*x{7}/);
  assert.deepEqual(named(gh, "createTag"), []);
  assert.match(named(gh, "comment")[0][2], /La publication de `v1\.0\.0-BETA\.97` a échoué/);
});

test("existingRelease_isNotRepublished", async () => {
  // A re-run after a full publication: the tag is there, so no new tag push re-triggers release.yml.
  const gh = fakeGitHub({ tags: { "v1.0.0-BETA.97": sha("m") } });
  await run(gh, mergedPull());
  assert.deepEqual(named(gh, "createTag"), []);
  assert.deepEqual(named(gh, "createPull"), []);
});

test("titleWithoutVersion_fails", async () => {
  const gh = fakeGitHub();
  await assert.rejects(run(gh, mergedPull({ title: "chore: release" })), /title/);
  await assert.rejects(run(gh, mergedPull({ title: "chore(release): v1.0.0" })), /title/, "a stable into beta");
  assert.deepEqual(named(gh, "createTag"), []);
});

test("unmergedOrUnrelatedPull_isRejected", async () => {
  const gh = fakeGitHub();
  await assert.rejects(run(gh, mergedPull({ merged: false })), /not merged/);
  await assert.rejects(run(gh, mergedPull({ base: "dev", head: "feat/x" })), /promotion/);
  await assert.rejects(run(gh, mergedPull({ headRepo: "someone/catapult" })), /promotion/);
  assert.deepEqual(named(gh, "createTag"), []);
});

test("hotfixMerge_publishesPatchAndOpensBackports", async () => {
  const gh = fakeGitHub({
    compare: {
      "beta...main": [commit("h", "fix: login")],
      "dev...beta": [commit("r", "chore(release): v1.0.1-BETA.1")],
    },
  });
  const result = await run(
    gh,
    mergedPull({ base: "main", head: "hotfix/login", title: "chore(release): v1.0.1", body: "Corrige le login." }),
  );
  assert.equal(result.tag, "v1.0.1");
  assert.equal(result.backports.length, 2);
  assert.deepEqual(named(gh, "createTag"), [
    ["createTag", { tag: "v1.0.1", sha: sha("m"), message: "chore(release): v1.0.1" }],
  ]);
  const created = named(gh, "createPull").map(([, fields]) => fields);
  assert.deepEqual(
    created.map(({ base, head, title, draft, labels }) => ({ base, head, title, draft, labels })),
    [
      { base: "beta", head: "main", title: "chore(release): backport v1.0.1 to beta", draft: false, labels: ["backport"] },
      { base: "dev", head: "beta", title: "chore(release): backport v1.0.1 to dev", draft: false, labels: ["backport"] },
    ],
  );
  for (const { body } of created) assert.match(body, /v1\.0\.1.*#7/);
});

test("hotfixBackports_skipOpenOrEmptyPairs", async () => {
  const gh = fakeGitHub({
    pulls: [{ number: 40, base: "beta", head: "main", labels: ["backport"] }],
    compare: { "beta...main": [commit("h", "fix: login")] },
  });
  const result = await run(gh, mergedPull({ base: "main", head: "hotfix/login", title: "chore(release): v1.0.1" }));
  assert.deepEqual(result.backports, []);
  assert.deepEqual(named(gh, "createPull"), []);
});

test("betaTag_refreshesTheStablePromotionWithTheNewTag", async () => {
  const gh = fakeGitHub();
  await run(gh, mergedPull());
  const names = gh.calls.map(([name]) => name);
  assert.deepEqual(named(gh, "promote"), [["promote", { source: "beta", target: "main", appSlug: "catapult-release" }]]);
  assert.ok(names.indexOf("createTag") < names.indexOf("promote"), "refreshed once the tag exists");
});

test("stableTag_doesNotRefreshTheStablePromotion", async () => {
  const gh = fakeGitHub();
  await run(gh, mergedPull({ base: "main", head: "beta", title: "chore(release): v1.0.0" }));
  assert.deepEqual(named(gh, "promote"), []);
});

test("refreshFailure_afterTheBetaTag_isOnlyLogged", async () => {
  const gh = fakeGitHub();
  const failing = async () => {
    throw new Error("promote exploded");
  };
  assert.deepEqual(await run(gh, mergedPull(), failing), { tag: "v1.0.0-BETA.97", backports: [] });
  assert.deepEqual(named(gh, "comment"), []);
});
