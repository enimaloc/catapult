import test from "node:test";
import assert from "node:assert/strict";
import { onReady, bumpGradleVersion } from "../ready.mjs";
import { buildBody } from "../lib/pr-body.mjs";

const OWNER = "enimaloc";
const APP = "catapult-release";
const sha = (c) => c.repeat(40);
const commit = (c, subject, login = OWNER) => ({ sha: sha(c), subject, message: subject, author: login, parents: [sha("0")] });

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

// In-memory GitHub holding one pull request (#7) and the branches' build.gradle.kts.
function fakeGitHub({
  base = "beta",
  head = "dev",
  title = "chore(release): v1.0.0-BETA.97",
  headSha = sha("a"),
  ahead = [commit("a", "feat(web): dark mode")],
  seen = ahead.map((c) => c.sha),
  gradle = GRADLE,
  others = [],
} = {}) {
  const calls = [];
  const pull = {
    number: 7,
    nodeId: "PR_7",
    title,
    body: buildBody({ changelog: "", botEnabled: true, seen }),
    draft: false,
    autoMerge: false,
    base,
    head,
    headSha,
    labels: ["release"],
  };
  const gh = {
    calls,
    async getPull(number) {
      calls.push(["getPull", number]);
      return pull;
    },
    async listPulls({ base: b }) {
      calls.push(["listPulls", b]);
      return [pull, ...others].filter((p) => p.base === b);
    },
    async compareCommits(b, h) {
      calls.push(["compareCommits", b, h]);
      return ahead;
    },
    async getFile(path, ref) {
      calls.push(["getFile", path, ref]);
      return { text: gradle, sha: "blob1" };
    },
    async updateFile(file) {
      calls.push(["updateFile", file]);
      return sha("b");
    },
    async enableAutoMerge(p, options) {
      calls.push(["enableAutoMerge", p.number, options]);
    },
    async convertToDraft(p) {
      calls.push(["convertToDraft", p.number]);
    },
    async comment(number, body) {
      calls.push(["comment", number, body]);
    },
  };
  return gh;
}

const run = (gh, sender = OWNER, headSha = sha("a")) =>
  onReady({ gh, pr: { number: 7, headSha }, sender, owner: OWNER, appSlug: APP });
const named = (gh, name) => gh.calls.filter((c) => c[0] === name);

test("bumpGradleVersion_replacesOnlyTheVersionLine", () => {
  const out = bumpGradleVersion(GRADLE, "1.0.0-BETA.97");
  assert.equal(out, GRADLE.replace('version = "0.3.7-BETA"', 'version = "1.0.0-BETA.97"'));
  assert.match(out, /version "7\.5\.0\.8588"/);
  assert.match(out, /version = "should-not-change"/);
});

test("bumpGradleVersion_throwsWithoutVersionLine", () => {
  assert.throws(() => bumpGradleVersion('group = "fr.enimaloc"\n', "1.0.0"), /version/);
});

test("ownerReady_bumpsGradleVersion_thenAutoMergesOnBumpSha", async () => {
  const gh = fakeGitHub();
  assert.equal(await run(gh), "auto-merge");
  assert.deepEqual(named(gh, "getFile"), [["getFile", "build.gradle.kts", sha("a")]]);
  const [[, file]] = named(gh, "updateFile");
  assert.deepEqual(file, {
    path: "build.gradle.kts",
    branch: "dev",
    sha: "blob1",
    message: "chore(release): v1.0.0-BETA.97",
    text: bumpGradleVersion(GRADLE, "1.0.0-BETA.97"),
  });
  assert.deepEqual(named(gh, "enableAutoMerge"), [
    ["enableAutoMerge", 7, { expectedHeadOid: sha("b"), commitHeadline: "chore(release): v1.0.0-BETA.97" }],
  ]);
  assert.deepEqual(named(gh, "convertToDraft"), []);
});

test("alreadyBumped_noCommit_autoMergesOnHeadSha", async () => {
  const gh = fakeGitHub({ gradle: bumpGradleVersion(GRADLE, "1.0.0-BETA.97") });
  assert.equal(await run(gh), "auto-merge");
  assert.deepEqual(named(gh, "updateFile"), []);
  assert.deepEqual(named(gh, "enableAutoMerge"), [
    ["enableAutoMerge", 7, { expectedHeadOid: sha("a"), commitHeadline: "chore(release): v1.0.0-BETA.97" }],
  ]);
});

test("ownerReady_enablesAutoMerge_withHeadSha", async () => {
  // A previous bump by the App (PR re-drafted, then made ready again) is not an unseen commit.
  const ahead = [commit("a", "feat(web): dark mode"), commit("c", "chore(release): v1.0.0-BETA.97", `${APP}[bot]`)];
  const gh = fakeGitHub({
    headSha: sha("c"),
    ahead,
    seen: [sha("a")],
    gradle: bumpGradleVersion(GRADLE, "1.0.0-BETA.97"),
  });
  assert.equal(await run(gh, OWNER, sha("c")), "auto-merge");
  assert.deepEqual(named(gh, "compareCommits"), [["compareCommits", "beta", sha("c")]]);
  assert.equal(named(gh, "enableAutoMerge")[0][2].expectedHeadOid, sha("c"));
});

test("stablePromotion_bumpsToTheStableVersion", async () => {
  const gh = fakeGitHub({ base: "main", head: "beta", title: "chore(release): v1.0.0" });
  assert.equal(await run(gh), "auto-merge");
  const [[, file]] = named(gh, "updateFile");
  assert.equal(file.branch, "beta");
  assert.match(file.text, /version = "1\.0\.0"\n/);
});

test("hotfix_isBumpedWithoutSeenMarker", async () => {
  const gh = fakeGitHub({ base: "main", head: "hotfix/login", title: "chore(release): v1.0.1", seen: [] });
  assert.equal(await run(gh), "auto-merge");
  assert.equal(named(gh, "updateFile")[0][1].branch, "hotfix/login");
});

test("otherSender_isIgnored", async () => {
  const gh = fakeGitHub();
  assert.equal(await run(gh, "someone-else"), "ignored");
  assert.deepEqual(gh.calls, []);
});

test("unrelatedPr_isIgnored", async () => {
  for (const [base, head] of [["dev", "feat/x"], ["main", "dev"], ["beta", "hotfix/x"], ["main", "feat/x"]]) {
    const gh = fakeGitHub({ base, head });
    assert.equal(await run(gh), "ignored");
    assert.deepEqual(gh.calls, [["getPull", 7]]);
  }
});

test("pendingBackport_refusesAndRedrafts", async () => {
  const backport = { number: 9, base: "beta", head: "main", labels: ["backport"] };
  const gh = fakeGitHub({ others: [backport] });
  assert.equal(await run(gh), "refused");
  assert.deepEqual(named(gh, "convertToDraft"), [["convertToDraft", 7]]);
  const [[, number, body]] = named(gh, "comment");
  assert.equal(number, 7);
  assert.match(body, /#9/);
  assert.deepEqual(named(gh, "updateFile"), []);
  assert.deepEqual(named(gh, "enableAutoMerge"), []);
});

test("unseenCommit_refusesAndRedrafts", async () => {
  const ahead = [commit("a", "feat(web): dark mode"), commit("d", "fix(api): late fix")];
  const gh = fakeGitHub({ headSha: sha("d"), ahead, seen: [sha("a")] });
  assert.equal(await run(gh, OWNER, sha("d")), "refused");
  assert.deepEqual(named(gh, "convertToDraft"), [["convertToDraft", 7]]);
  assert.match(named(gh, "comment")[0][2], /ddddddd/);
  assert.deepEqual(named(gh, "enableAutoMerge"), []);
});

test("headMovedSinceTheEvent_refusesAndRedrafts", async () => {
  const gh = fakeGitHub({ headSha: sha("e") });
  assert.equal(await run(gh, OWNER, sha("a")), "refused");
  assert.deepEqual(named(gh, "convertToDraft"), [["convertToDraft", 7]]);
  assert.deepEqual(named(gh, "enableAutoMerge"), []);
});

test("titleWithoutMatchingVersion_refusesAndRedrafts", async () => {
  for (const [base, head, title] of [
    ["main", "hotfix/login", "fix: login"],
    ["beta", "dev", "chore(release): v1.0.0"],
    ["main", "beta", "chore(release): v1.0.0-BETA.3"],
  ]) {
    const gh = fakeGitHub({ base, head, title });
    assert.equal(await run(gh), "refused");
    assert.deepEqual(named(gh, "convertToDraft"), [["convertToDraft", 7]]);
    assert.match(named(gh, "comment")[0][2], /chore\(release\): v/);
  }
});
