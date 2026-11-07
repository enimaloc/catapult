import test from "node:test";
import assert from "node:assert/strict";
import { notesFor } from "../notes.mjs";
import { buildBody } from "../lib/pr-body.mjs";

const sha = (c) => c.repeat(40);
const CHANGELOG = "### Corrections\n\n- abc1234 edited by hand";
const body = buildBody({ changelog: CHANGELOG, botEnabled: true, seen: [], promotedHead: sha("a") });
const pull = (fields) => ({ number: 7, title: "chore(release): v1.0.0-BETA.97", body, merged: true, mergeCommitSha: sha("m"), ...fields });

// In-memory GitHub holding the closed pulls of each base.
function fakeGitHub(closed) {
  const calls = [];
  return {
    calls,
    async listPulls({ base, state }) {
      calls.push(["listPulls", base, state]);
      return closed[base] ?? [];
    },
  };
}

test("mergedReleasePull_givesItsChangelog", async () => {
  const gh = fakeGitHub({ beta: [pull({ number: 6, title: "chore(release): v1.0.0-BETA.96" }), pull()] });
  assert.equal(await notesFor({ gh, tag: "v1.0.0-BETA.97", sha: sha("m") }), CHANGELOG);
  assert.deepEqual(gh.calls, [["listPulls", "beta", "closed"]]);
});

test("stableTag_looksIntoMain", async () => {
  const gh = fakeGitHub({ main: [pull({ title: "chore(release): v1.0.0" })] });
  assert.equal(await notesFor({ gh, tag: "v1.0.0", sha: sha("m") }), CHANGELOG);
  assert.deepEqual(gh.calls, [["listPulls", "main", "closed"]]);
});

test("noMatchingPull_givesEmptyNotes", async () => {
  const gh = fakeGitHub({
    beta: [
      pull({ merged: false }),
      pull({ mergeCommitSha: sha("x") }),
      pull({ title: "chore(release): v1.0.0-BETA.98" }),
    ],
  });
  assert.equal(await notesFor({ gh, tag: "v1.0.0-BETA.97", sha: sha("m") }), "");
});

test("manualTag_withoutPull_givesEmptyNotes", async () => {
  assert.equal(await notesFor({ gh: fakeGitHub({}), tag: "v1.0.0", sha: sha("m") }), "");
});
