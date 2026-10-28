import test from "node:test";
import assert from "node:assert/strict";
import { publish } from "../publish.mjs";
import { buildBody } from "../lib/pr-body.mjs";

const OWNER = "enimaloc";
const REPO = "catapult";
const sha = (c) => c.repeat(40);
const CHANGELOG = "### Fonctionnalités\n\n- abc1234 edited by hand";

// In-memory GitHub: tags map name -> commit SHA, releases a set of tags, pulls the open ones.
function fakeGitHub({ tags = {}, releases = [], pulls = [], compare = {} } = {}) {
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
    async getRelease(tag) {
      record("getRelease", tag);
      return releases.includes(tag) ? { tag } : null;
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

const run = (gh, pr) => publish({ gh, pr, owner: OWNER, repo: REPO });
const named = (gh, name) => gh.calls.filter((c) => c[0] === name);
const commit = (c, subject) => ({ sha: sha(c), subject, message: subject, author: OWNER, parents: [sha("0")] });

test("merge_tagsMergeCommit_withNotesFromBody", async () => {
  const gh = fakeGitHub();
  const result = await run(gh, mergedPull());
  assert.deepEqual(result, {
    tag: "v1.0.0-BETA.97",
    prerelease: true,
    notes: CHANGELOG,
    skip: false,
    backports: [],
  });
  assert.deepEqual(named(gh, "createTag"), [
    ["createTag", { tag: "v1.0.0-BETA.97", sha: sha("m"), message: "chore(release): v1.0.0-BETA.97" }],
  ]);
  assert.deepEqual(named(gh, "createPull"), []);
});

test("stableMerge_isNotAPrerelease", async () => {
  const gh = fakeGitHub();
  const result = await run(gh, mergedPull({ base: "main", head: "beta", title: "chore(release): v1.0.0" }));
  assert.equal(result.tag, "v1.0.0");
  assert.equal(result.prerelease, false);
  assert.deepEqual(named(gh, "createPull"), [], "only a hotfix opens backports");
});

test("existingTagOnMergeCommit_isKept", async () => {
  const gh = fakeGitHub({ tags: { "v1.0.0-BETA.97": sha("m") } });
  const result = await run(gh, mergedPull());
  assert.equal(result.skip, false);
  assert.deepEqual(named(gh, "createTag"), []);
});

test("existingTagOnAnotherCommit_failsLoudly", async () => {
  const gh = fakeGitHub({ tags: { "v1.0.0-BETA.97": sha("x") } });
  await assert.rejects(run(gh, mergedPull()), /v1\.0\.0-BETA\.97.*x{7}/);
  assert.deepEqual(named(gh, "createTag"), []);
  assert.match(named(gh, "comment")[0][2], /La publication de `v1\.0\.0-BETA\.97` a échoué/);
});

test("existingRelease_isNotRepublished", async () => {
  const gh = fakeGitHub({ tags: { "v1.0.0-BETA.97": sha("m") }, releases: ["v1.0.0-BETA.97"] });
  const result = await run(gh, mergedPull());
  assert.equal(result.skip, true);
  assert.equal(result.tag, "v1.0.0-BETA.97");
  assert.deepEqual(named(gh, "createTag"), []);
});

test("emptyChangelog_leavesNotesEmpty", async () => {
  const gh = fakeGitHub();
  const result = await run(gh, mergedPull({ body: "" }));
  assert.equal(result.notes, "");
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
  assert.equal(result.prerelease, false);
  assert.equal(result.notes, "");
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
