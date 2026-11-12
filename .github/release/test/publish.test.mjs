import test from "node:test";
import assert from "node:assert/strict";
import { publish } from "../publish.mjs";
import { buildBody } from "../lib/pr-body.mjs";

const OWNER = "enimaloc";
const REPO = "catapult";
const sha = (c) => c.repeat(40);
const CHANGELOG = "### Fonctionnalités\n\n- abc1234 edited by hand";

// In-memory GitHub: tags map name -> commit SHA, pulls the open ones.
function fakeGitHub({ tags = {}, pulls = [], compare = {}, autoMergeFails = false } = {}) {
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
      return { number: 100 + calls.length, nodeId: "PR_N", headSha: sha("h"), ...fields };
    },
    async enableAutoMerge(pull, options) {
      record("enableAutoMerge", pull.number, options);
      if (autoMergeFails) throw new Error("Pull request is in clean status");
    },
    async updatePull(number, fields) {
      record("updatePull", number, fields);
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

// `beta`: whether the chain goes through beta (lib/flow.mjs); left out, the current chain.
const run = (gh, pr, beta) => publish({ gh, pr, owner: OWNER, repo: REPO, beta });
const withBeta = (gh, pr) => run(gh, pr, true);
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

const hotfix = (fields = {}) =>
  mergedPull({ base: "main", head: "hotfix/login", title: "chore(release): v1.0.1", body: "Corrige le login.", ...fields });
const opened = (gh) =>
  named(gh, "createPull").map(([, { base, head, title, draft, labels }]) => ({ base, head, title, draft, labels }));
const backport = (base, head, tag) => ({
  base,
  head,
  title: `chore(release): backport ${tag} to ${base}`,
  draft: false,
  labels: ["backport"],
});

test("hotfixMerge_publishesPatch_andOpensOnlyTheBackportToBeta", async () => {
  const gh = fakeGitHub({
    compare: {
      "beta...main": [commit("h", "fix: login")],
      "dev...beta": [commit("r", "chore(release): v1.0.1-BETA.1")],
    },
  });
  const result = await withBeta(gh, hotfix());
  assert.equal(result.tag, "v1.0.1");
  assert.equal(result.backports.length, 1);
  assert.deepEqual(named(gh, "createTag"), [
    ["createTag", { tag: "v1.0.1", sha: sha("m"), message: "chore(release): v1.0.1" }],
  ]);
  assert.deepEqual(opened(gh), [backport("beta", "main", "v1.0.1")], "beta -> dev waits for main -> beta");
  assert.match(named(gh, "createPull")[0][1].body, /v1\.0\.1.*#7/);
});

test("hotfixBackports_skipOpenOrEmptyPairs", async () => {
  const gh = fakeGitHub({
    pulls: [{ number: 40, base: "beta", head: "main", labels: ["backport"] }],
    compare: { "beta...main": [commit("h", "fix: login")], "dev...beta": [commit("r", "fix: r")] },
  });
  const result = await withBeta(gh, hotfix());
  assert.deepEqual(result.backports, []);
  assert.deepEqual(named(gh, "createPull"), [], "the open main -> beta is reused; beta -> dev waits for it");
});

test("hotfixWithNothingToBringToBeta_opensTheBackportToDevRightAway", async () => {
  const gh = fakeGitHub({ compare: { "dev...beta": [commit("r", "fix: r")] } });
  const result = await withBeta(gh, hotfix());
  assert.equal(result.backports.length, 1);
  assert.deepEqual(opened(gh), [backport("dev", "beta", "v1.0.1")]);
});

test("stableMerge_opensTheBackportOfBetaToDev", async () => {
  const gh = fakeGitHub({ compare: { "dev...beta": [commit("r", "chore(release): v1.0.0")] } });
  const result = await run(gh, mergedPull({ base: "main", head: "beta", title: "chore(release): v1.0.0" }));
  assert.equal(result.backports.length, 1);
  assert.deepEqual(opened(gh), [backport("dev", "beta", "v1.0.0")]);
  assert.match(named(gh, "createPull")[0][1].body, /v1\.0\.0.*#7/);
});

test("stableMerge_reusesAnOpenBackportToDev", async () => {
  const gh = fakeGitHub({
    pulls: [{ number: 41, base: "dev", head: "beta", labels: ["backport"] }],
    compare: { "dev...beta": [commit("r", "chore(release): v1.0.0")] },
  });
  const result = await run(gh, mergedPull({ base: "main", head: "beta", title: "chore(release): v1.0.0" }));
  assert.deepEqual(result.backports, []);
  assert.deepEqual(named(gh, "createPull"), []);
});

// Beta skipped (lib/flow.mjs): dev -> main is a stable, brought back to dev straight from main.
test("devToMainMerge_tagsTheStable_andOpensTheBackportOfMainToDev", async () => {
  const gh = fakeGitHub({ compare: { "dev...main": [commit("r", "chore(release): v1.2.0")] } });
  const result = await run(gh, mergedPull({ base: "main", head: "dev", title: "chore(release): v1.2.0" }), false);
  assert.equal(result.tag, "v1.2.0");
  assert.deepEqual(named(gh, "createTag"), [
    ["createTag", { tag: "v1.2.0", sha: sha("m"), message: "chore(release): v1.2.0" }],
  ]);
  assert.deepEqual(opened(gh), [backport("dev", "main", "v1.2.0")]);
});

test("hotfixWithoutBeta_opensTheBackportOfMainToDev", async () => {
  const gh = fakeGitHub({ compare: { "beta...main": [commit("h", "fix: login")], "dev...main": [commit("h", "fix: login")] } });
  const result = await run(gh, hotfix(), false);
  assert.equal(result.backports.length, 1);
  assert.deepEqual(opened(gh), [backport("dev", "main", "v1.0.1")]);
});

test("betaMerge_opensNoBackport", async () => {
  const gh = fakeGitHub({ compare: { "dev...beta": [commit("r", "fix: r")] } });
  await run(gh, mergedPull());
  assert.deepEqual(named(gh, "createPull"), []);
});

const mergedBackport = (labels = ["backport"]) => ({
  ...mergedPull({ base: "beta", head: "main", title: "chore(release): backport v1.0.1 to beta" }),
  labels,
});

test("mergedBackportToBeta_opensTheBackportToDev", async () => {
  const gh = fakeGitHub({ compare: { "dev...beta": [commit("r", "fix: login")] } });
  const result = await run(gh, mergedBackport());
  assert.equal(result.tag, "v1.0.1");
  assert.equal(result.backports.length, 1);
  assert.deepEqual(opened(gh), [backport("dev", "beta", "v1.0.1")]);
  assert.deepEqual(named(gh, "createTag"), [], "a backport tags nothing");
});

test("mergedBackportToBeta_withoutBackportLabel_isRejected", async () => {
  const gh = fakeGitHub({ compare: { "dev...beta": [commit("r", "fix: login")] } });
  await assert.rejects(run(gh, mergedBackport([])), /promotion/);
  assert.deepEqual(named(gh, "createPull"), []);
});

// The beta -> main promotion is publish.yml's refresh-stable job's, under promote-beta: a beta's publication
// opens no pull request itself, else it races promote.yml's run for the same push to beta.
test("betaTag_opensNoPullRequest", async () => {
  const gh = fakeGitHub();
  assert.deepEqual(await run(gh, mergedPull()), { tag: "v1.0.0-BETA.97", backports: [] });
  assert.deepEqual(named(gh, "createPull"), []);
  assert.deepEqual(named(gh, "listPulls"), []);
});

test("mergedHotfixWithoutVersion_isReportedOnThePr", async () => {
  const gh = fakeGitHub();
  await assert.rejects(run(gh, hotfix({ title: "fix: login" })), /title/);
  assert.deepEqual(named(gh, "createTag"), []);
  const [[, number, text]] = named(gh, "comment");
  assert.equal(number, 7);
  assert.match(text, /`chore\(release\): vX\.Y\.Z`[\s\S]*aucun tag[\s\S]*tag annoté `vX\.Y\.Z`/);
});
