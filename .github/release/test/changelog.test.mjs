import test from "node:test";
import assert from "node:assert/strict";
import { parseTagDates, updateChangelog } from "../changelog.mjs";
import { renderChangelogFile } from "../lib/changelog-file.mjs";

const RELEASES = [
  { tag: "v1.0.0-BETA.2", body: "### Fixes\n- b", publishedAt: "2026-10-28T10:00:00Z" },
  { tag: "v1.0.0-BETA.1", body: "### Features\n- a", publishedAt: "2026-10-28T09:00:00Z" },
];
const DATES = new Map([["v1.0.0-BETA.1", "2026-03-30"]]);
const EXPECTED = renderChangelogFile(
  [
    { tag: "v1.0.0-BETA.2", body: "### Fixes\n- b", date: "2026-10-28" },
    { tag: "v1.0.0-BETA.1", body: "### Features\n- a", date: "2026-03-30" },
  ],
  "o/r",
);

// In-memory GitHub: the branch heads in turn (one per getBranch), the file per head, the commit outcomes.
function fakeGitHub({ heads = ["h1"], files = {}, commits = ["c1"] } = {}) {
  const calls = [];
  return {
    calls,
    async listAllReleases() {
      return RELEASES;
    },
    async getBranch(name) {
      calls.push(["getBranch", name]);
      return heads.length > 1 ? heads.shift() : heads[0];
    },
    async getFile(path, ref) {
      if (!(ref in files)) throw Object.assign(new Error("Not Found"), { status: 404 });
      return { text: files[ref] };
    },
    async commitFile(change) {
      calls.push(["commitFile", change]);
      return commits.shift() ?? null;
    },
  };
}

const run = (gh) => updateChangelog({ gh, tag: "v1.0.0-BETA.2", branch: "dev", dates: DATES, repository: "o/r" });

test("missingFile_isCommittedOnTheBranchAsAReleaseCommit", async () => {
  const gh = fakeGitHub();
  assert.equal(await run(gh), "updated");
  assert.deepEqual(gh.calls.at(-1), [
    "commitFile",
    { branch: "dev", parent: "h1", path: "CHANGELOG.md", text: EXPECTED, message: "chore(release): v1.0.0-BETA.2 changelog" },
  ]);
});

test("upToDateFile_isLeftAlone", async () => {
  const gh = fakeGitHub({ files: { h1: EXPECTED } });
  assert.equal(await run(gh), "unchanged");
  assert.ok(!gh.calls.some(([name]) => name === "commitFile"));
});

test("branchMoved_retriesOnTheNewHead", async () => {
  const gh = fakeGitHub({ heads: ["h1", "h2"], files: { h1: "old", h2: "old" }, commits: [null, "c2"] });
  assert.equal(await run(gh), "updated");
  const parents = gh.calls.filter(([name]) => name === "commitFile").map(([, change]) => change.parent);
  assert.deepEqual(parents, ["h1", "h2"]);
});

test("branchKeepsMoving_throws", async () => {
  await assert.rejects(run(fakeGitHub({ commits: [null, null, null] })), /kept moving/);
});

test("missingBranch_throws", async () => {
  await assert.rejects(run(fakeGitHub({ heads: [null] })), /No dev branch/);
});

test("tagDates_preferThePeeledCommitDate", () => {
  const dates = parseTagDates("v1\t2026-03-30\t\nv2\t\t2026-04-01\nv3\t\t\n");
  assert.deepEqual([...dates], [["v1", "2026-03-30"], ["v2", "2026-04-01"]]);
});
