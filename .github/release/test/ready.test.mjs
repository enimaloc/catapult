import test from "node:test";
import assert from "node:assert/strict";
import { onReady, bumpGradleVersion } from "../ready.mjs";
import { buildBody } from "../lib/pr-body.mjs";

const OWNER = "enimaloc";
const REPO = "catapult";
const APP = "catapult-release";
const sha = (c) => c.repeat(40);
const commit = (c, subject, login = OWNER) => ({ sha: sha(c), subject, message: subject, author: login, parents: [sha("0")] });
const bump = (c, version) => commit(c, `chore(release): ${version}`, `${APP}[bot]`);

const GRADLE = [
  "plugins {",
  '    id("org.sonarqube") version "7.5.0.8588" apply false',
  "}",
  "",
  "allprojects {",
  '    group = "fr.enimaloc"',
  '    version = "0.3.7-BETA"',
  "}",
  "",
  "subprojects {",
  '    version = "should-not-change"',
  "}",
  "",
].join("\n");
const BUMPED = bumpGradleVersion(GRADLE, "1.0.0-BETA.97");

// In-memory GitHub holding one pull request (#7) and the head's build.gradle.kts.
function fakeGitHub({
  base = "beta",
  head = "dev",
  headRepo = `${OWNER}/${REPO}`,
  title = "chore(release): v1.0.0-BETA.97",
  headSha = sha("a"),
  marker = headSha,
  after = [], // commits of compare(marker, head)
  gradle = GRADLE,
  others = [],
  tags = ["v0.0.0", "v1.0.0-BETA.96"],
  ahead = [commit("a", "fix(web): one")], // commits of compare(base, head)
  mergeState = "BLOCKED",
  moved = false, // the branch moved before the bump ref update
  later = {}, // fields of the pull on the second getPull
  failOn = null,
} = {}) {
  const calls = [];
  const body = buildBody({ changelog: "", botEnabled: true, seen: [], promotedHead: marker });
  const pull = { number: 7, nodeId: "PR_7", title, body, state: "open", draft: false, base, head, headSha, headRepo };
  let reads = 0;
  const record = (name, ...args) => {
    calls.push([name, ...args]);
    if (failOn === name) throw new Error(`${name} exploded`);
  };
  return {
    calls,
    async getPull(number) {
      record("getPull", number);
      return reads++ === 0 ? pull : { ...pull, ...later };
    },
    async listPulls({ base: b }) {
      record("listPulls", b);
      return [pull, ...others].filter((p) => p.base === b);
    },
    async listTags() {
      record("listTags");
      return tags;
    },
    async updatePull(number, fields) {
      record("updatePull", number, fields);
    },
    async compareCommits(b, h) {
      record("compareCommits", b, h);
      return b === base && h === head ? ahead : after;
    },
    async getTag(name) {
      record("getTag", name);
      return tags.includes(name) ? sha("9") : null;
    },
    async getFile(path, ref) {
      record("getFile", path, ref);
      return { text: gradle, sha: "blob1" };
    },
    async commitFile(file) {
      record("commitFile", file);
      return moved ? null : sha("b");
    },
    async getMergeState(p) {
      record("getMergeState", p.number);
      return mergeState;
    },
    async mergePull(p, options) {
      record("mergePull", p.number, options);
    },
    async enableAutoMerge(p, options) {
      record("enableAutoMerge", p.number, options);
    },
    async convertToDraft(p) {
      record("convertToDraft", p.number);
    },
    async comment(number, text) {
      record("comment", number, text);
    },
  };
}

// promote.mjs stand-in: records the refresh ready.mjs asks for.
const fakePromote = (gh) => async (args) => gh.calls.push(["promote", { source: args.source, target: args.target }]);
const run = (gh, { sender = OWNER, headSha = sha("a") } = {}) =>
  onReady({ gh, pr: { number: 7, headSha }, sender, owner: OWNER, repo: REPO, appSlug: APP, promote: fakePromote(gh) });
const named = (gh, name) => gh.calls.filter((c) => c[0] === name);
const assertRefused = (gh, pattern) => {
  assert.deepEqual(named(gh, "convertToDraft"), [["convertToDraft", 7]]);
  assert.match(named(gh, "comment")[0][2], pattern);
  assert.deepEqual(named(gh, "commitFile"), []);
  assert.deepEqual(named(gh, "enableAutoMerge"), []);
  assert.deepEqual(named(gh, "mergePull"), []);
};

test("bumpGradleVersion_replacesOnlyTheVersionLine", () => {
  assert.equal(BUMPED, GRADLE.replace('version = "0.3.7-BETA"', 'version = "1.0.0-BETA.97"'));
  assert.match(BUMPED, /version "7\.5\.0\.8588"/);
  assert.match(BUMPED, /version = "should-not-change"/);
});

test("bumpGradleVersion_throwsWithoutVersionLine", () => {
  assert.throws(() => bumpGradleVersion('group = "fr.enimaloc"\n', "1.0.0"), /version/);
});

test("ownerReady_bumpsGradleVersion_thenAutoMergesOnBumpSha", async () => {
  const gh = fakeGitHub();
  assert.equal(await run(gh), "auto-merge");
  assert.deepEqual(named(gh, "getFile"), [["getFile", "build.gradle.kts", sha("a")]]);
  assert.deepEqual(named(gh, "commitFile"), [
    [
      "commitFile",
      { branch: "dev", parent: sha("a"), path: "build.gradle.kts", text: BUMPED, message: "chore(release): v1.0.0-BETA.97" },
    ],
  ]);
  assert.deepEqual(named(gh, "enableAutoMerge"), [
    ["enableAutoMerge", 7, { expectedHeadOid: sha("b"), commitHeadline: "chore(release): v1.0.0-BETA.97" }],
  ]);
  assert.deepEqual(named(gh, "convertToDraft"), []);
  assert.deepEqual(named(gh, "compareCommits"), [["compareCommits", "beta", "dev"]], "marker === head: no compare");
  assert.deepEqual(named(gh, "getTag"), [["getTag", "v1.0.0-BETA.97"]]);
  assert.deepEqual(named(gh, "promote"), []);
});

test("alreadyBumped_noCommit_autoMergesOnHeadSha", async () => {
  const gh = fakeGitHub({ gradle: BUMPED });
  assert.equal(await run(gh), "auto-merge");
  assert.deepEqual(named(gh, "commitFile"), []);
  assert.deepEqual(named(gh, "enableAutoMerge"), [
    ["enableAutoMerge", 7, { expectedHeadOid: sha("a"), commitHeadline: "chore(release): v1.0.0-BETA.97" }],
  ]);
});

test("alreadyBumped_cleanPr_isMergedRightAway", async () => {
  const gh = fakeGitHub({ gradle: BUMPED, mergeState: "CLEAN" });
  assert.equal(await run(gh), "merged");
  assert.deepEqual(named(gh, "mergePull"), [
    ["mergePull", 7, { expectedHeadOid: sha("a"), commitHeadline: "chore(release): v1.0.0-BETA.97" }],
  ]);
  assert.deepEqual(named(gh, "enableAutoMerge"), []);
});

test("ownerReady_enablesAutoMerge_withHeadSha", async () => {
  // Only the App's previous bump came after the promoted head (PR re-drafted, then made ready again).
  const gh = fakeGitHub({ headSha: sha("c"), marker: sha("a"), after: [bump("c", "v1.0.0-BETA.97")], gradle: BUMPED });
  assert.equal(await run(gh, { headSha: sha("c") }), "auto-merge");
  assert.deepEqual(named(gh, "compareCommits").at(-1), ["compareCommits", sha("a"), sha("c")]);
  assert.equal(named(gh, "enableAutoMerge")[0][2].expectedHeadOid, sha("c"));
});

test("stablePromotion_bumpsToTheStableVersion", async () => {
  const gh = fakeGitHub({ base: "main", head: "beta", title: "chore(release): v1.0.0" });
  assert.equal(await run(gh), "auto-merge");
  const [[, file]] = named(gh, "commitFile");
  assert.equal(file.branch, "beta");
  assert.match(file.text, /version = "1\.0\.0"\n/);
});

test("hotfixWithoutVersionTitle_getsTheNextPatch", async () => {
  const tags = ["v0.0.0", "v1.0.0"];
  const gh = fakeGitHub({ base: "main", head: "hotfix/login", title: "fix: login", marker: null, tags });
  assert.equal(await run(gh), "auto-merge");
  assert.deepEqual(named(gh, "updatePull"), [["updatePull", 7, { title: "chore(release): v1.0.1" }]]);
  const [[, file]] = named(gh, "commitFile");
  assert.equal(file.branch, "hotfix/login");
  assert.equal(file.message, "chore(release): v1.0.1");
  assert.match(file.text, /version = "1\.0\.1"\n/);
  assert.equal(named(gh, "enableAutoMerge")[0][2].commitHeadline, "chore(release): v1.0.1");
  assert.deepEqual(named(gh, "compareCommits"), []);
});

test("hotfixWithVersionTitle_keepsIt", async () => {
  const tags = ["v0.0.0", "v1.0.0"];
  const gh = fakeGitHub({ base: "main", head: "hotfix/login", title: "chore(release): v1.0.5", marker: null, tags });
  assert.equal(await run(gh), "auto-merge");
  assert.deepEqual(named(gh, "updatePull"), []);
  assert.equal(named(gh, "commitFile")[0][1].message, "chore(release): v1.0.5");
});

test("otherSender_isIgnored", async () => {
  const gh = fakeGitHub();
  assert.equal(await run(gh, { sender: "someone-else" }), "ignored");
  assert.deepEqual(gh.calls, []);
});

test("unrelatedPr_isIgnored", async () => {
  for (const [base, head] of [["dev", "feat/x"], ["main", "dev"], ["beta", "hotfix/x"], ["main", "feat/x"]]) {
    const gh = fakeGitHub({ base, head });
    assert.equal(await run(gh), "ignored");
    assert.deepEqual(gh.calls, [["getPull", 7]]);
  }
});

test("prFromAFork_isIgnored", async () => {
  const gh = fakeGitHub({ headRepo: "mallory/catapult" });
  assert.equal(await run(gh), "ignored");
  assert.deepEqual(gh.calls, [["getPull", 7]]);
});

test("pendingBackportIntoTheHeadBranch_refusesAndRedrafts", async () => {
  // After a stable release, beta -> dev must land before dev is promoted again.
  const gh = fakeGitHub({ others: [{ number: 11, base: "dev", head: "beta", labels: ["backport"] }] });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /#11/);
});

test("openNonBackportPrIntoTheHeadBranch_doesNotBlock", async () => {
  const gh = fakeGitHub({ others: [{ number: 12, base: "dev", head: "feat/x", labels: [] }] });
  assert.equal(await run(gh), "auto-merge");
});

test("pendingBackport_refusesAndRedrafts", async () => {
  const gh = fakeGitHub({ others: [{ number: 9, base: "beta", head: "main", labels: ["backport"] }] });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /#9/);
});

test("humanCommitAfterThePromotedHead_refusesAndRedrafts", async () => {
  const after = [commit("d", "fix(api): late fix"), bump("e", "v1.0.0-BETA.97")];
  const gh = fakeGitHub({ headSha: sha("e"), marker: sha("a"), after });
  assert.equal(await run(gh, { headSha: sha("e") }), "refused");
  assertRefused(gh, /ddddddd fix\(api\): late fix/);
});

test("missingPromotedHead_refusesAndRedrafts", async () => {
  const gh = fakeGitHub({ marker: null });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /tête de branche/);
});

test("headMovedSinceTheEvent_refusesAndRedrafts", async () => {
  const gh = fakeGitHub({ headSha: sha("e") });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /a bougé/);
});

test("headMovedDuringTheChecks_refusesAndRedrafts", async () => {
  const gh = fakeGitHub({ later: { headSha: sha("f") } });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /a bougé/);
});

test("branchMovedBeforeTheBumpLanded_refusesAndRedrafts", async () => {
  const gh = fakeGitHub({ moved: true });
  assert.equal(await run(gh), "refused");
  assert.deepEqual(named(gh, "convertToDraft"), [["convertToDraft", 7]]);
  assert.match(named(gh, "comment")[0][2], /a bougé/);
  assert.deepEqual(named(gh, "enableAutoMerge"), []);
});

test("redraftedOrClosedMeanwhile_isNotMerged", async () => {
  for (const later of [{ draft: true }, { state: "closed" }]) {
    const gh = fakeGitHub({ later });
    assert.equal(await run(gh), "refused");
    assert.deepEqual(named(gh, "convertToDraft"), [], "already out of the ready state");
    assert.equal(named(gh, "comment").length, 1);
    assert.deepEqual(named(gh, "commitFile"), []);
    assert.deepEqual(named(gh, "enableAutoMerge"), []);
  }
});

test("promotionTitleWithoutMatchingVersion_refusesAndRedrafts", async () => {
  for (const [base, head, title] of [
    ["beta", "dev", "chore(release): v1.0.0"],
    ["beta", "dev", "chore: release"],
    ["main", "beta", "chore(release): v1.0.0-BETA.3"],
  ]) {
    const gh = fakeGitHub({ base, head, title });
    assert.equal(await run(gh), "refused");
    assertRefused(gh, /chore\(release\): v/);
  }
});

test("apiFailure_redraftsWithTheError_thenRethrows", async () => {
  const gh = fakeGitHub({ failOn: "enableAutoMerge" });
  await assert.rejects(run(gh), /enableAutoMerge exploded/);
  assert.deepEqual(named(gh, "convertToDraft"), [["convertToDraft", 7]]);
  assert.match(named(gh, "comment")[0][2], /a échoué[\s\S]*enableAutoMerge exploded/);
});

test("titleTagAlreadyExists_refuses_andRefreshesThePromotion", async () => {
  const gh = fakeGitHub({ tags: ["v0.0.0", "v1.0.0-BETA.96", "v1.0.0-BETA.97"] });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /`v1\.0\.0-BETA\.97` existe déjà[\s\S]*prochaine mise à jour/);
  assert.deepEqual(named(gh, "promote"), [["promote", { source: "dev", target: "beta" }]]);
  assert.ok(gh.calls.findIndex(([n]) => n === "comment") < gh.calls.findIndex(([n]) => n === "promote"));
});

test("hotfixTitleTagAlreadyExists_refuses_withoutRefresh", async () => {
  const gh = fakeGitHub({
    base: "main",
    head: "hotfix/login",
    title: "chore(release): v1.0.0",
    marker: null,
    tags: ["v0.0.0", "v1.0.0"],
  });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /`v1\.0\.0` existe déjà[\s\S]*Corrige le titre/);
  assert.deepEqual(named(gh, "promote"), []);
});

test("staleBetaTitle_refuses_andRefreshesThePromotion", async () => {
  // v1.0.0 was released since the bot titled the PR: the next beta is v1.0.1-BETA.1.
  const gh = fakeGitHub({ tags: ["v0.0.0", "v1.0.0-BETA.96", "v1.0.0"] });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /`v1\.0\.0-BETA\.97`[\s\S]*`v1\.0\.1-BETA\.1`[\s\S]*prochaine mise à jour/);
  assert.deepEqual(named(gh, "promote"), [["promote", { source: "dev", target: "beta" }]]);
});

test("staleStableTitle_refuses_andRefreshesThePromotion", async () => {
  // A newer beta was tagged after the bot titled the stable PR.
  const gh = fakeGitHub({
    base: "main",
    head: "beta",
    title: "chore(release): v1.0.0",
    tags: ["v0.0.0", "v1.0.0-BETA.96", "v1.1.0-BETA.1"],
  });
  assert.equal(await run(gh), "refused");
  assertRefused(gh, /`v1\.1\.0`/);
  assert.deepEqual(named(gh, "promote"), [["promote", { source: "beta", target: "main" }]]);
});

test("refreshFailure_afterARefusal_isOnlyLogged", async () => {
  const gh = fakeGitHub({ tags: ["v0.0.0", "v1.0.0-BETA.96", "v1.0.0-BETA.97"] });
  const failing = async () => {
    throw new Error("promote exploded");
  };
  const result = await onReady({
    gh, pr: { number: 7, headSha: sha("a") }, sender: OWNER, owner: OWNER, repo: REPO, appSlug: APP, promote: failing,
  });
  assert.equal(result, "refused");
  assert.equal(named(gh, "comment").length, 1);
});
