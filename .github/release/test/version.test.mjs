import test from "node:test";
import assert from "node:assert/strict";
import { parseVersion, bumpKind, nextBeta, nextStable } from "../lib/version.mjs";

test("parseVersion reads stable and beta tags", () => {
  assert.deepEqual(parseVersion("v1.0.0-BETA.3"), { major: 1, minor: 0, patch: 0, beta: 3 });
  assert.deepEqual(parseVersion("v1.2.0"), { major: 1, minor: 2, patch: 0, beta: null });
});

test("parseVersion rejects other forms", () => {
  for (const bad of ["1.2.0", "v1.2", "v1.0.0-ALPHA.1", "v1.0.0-BETA", "v1.0.0-beta.1", "v1.0.0-BETA.", "v1.0.0-BETA.1x"]) {
    assert.throws(() => parseVersion(bad), undefined, bad);
  }
});

test("bumpKind picks the highest bump", () => {
  assert.equal(bumpKind([]), null);
  assert.equal(bumpKind(["fix: a"]), "patch");
  assert.equal(bumpKind(["chore(ci): a", "docs: b"]), "patch");
  assert.equal(bumpKind(["fix: a", "feat(api): b"]), "minor");
  assert.equal(bumpKind(["feat!: z"], "v1.4.0"), "major");
  assert.equal(bumpKind(["fix(api)!: z"], "v1.4.0"), "major");
  assert.equal(bumpKind(["fix: a\n\nBREAKING CHANGE: gone"], "v1.4.0"), "major");
});

test("bumpKind downgrades major to minor on 0.x", () => {
  assert.equal(bumpKind(["feat!: z"], "v0.3.0"), "minor");
});

test("bumpKind ignores non-conventional subjects", () => {
  assert.equal(bumpKind(["Merge branch 'x'"]), null);
});

test("nextBeta continues the migrated history", () => {
  assert.equal(nextBeta("v0.0.0", ["v1.0.0-BETA.96"], ["feat: x"]), "v1.0.0-BETA.97");
});

test("nextBeta starts at 1 for a new target", () => {
  assert.equal(nextBeta("v1.0.0", [], ["fix: y"]), "v1.0.1-BETA.1");
});

test("nextBeta moves to a higher target and restarts N", () => {
  assert.equal(nextBeta("v1.0.0", ["v1.0.1-BETA.2"], ["fix: a", "feat: b"]), "v1.1.0-BETA.1");
});

test("nextBeta increments within the same target", () => {
  assert.equal(nextBeta("v1.0.0", ["v1.0.1-BETA.1", "v1.0.1-BETA.2"], ["fix: a"]), "v1.0.1-BETA.3");
});

test("nextBeta ignores betas not newer than the last stable", () => {
  assert.equal(nextBeta("v1.1.0", ["v1.0.0-BETA.9", "v1.1.0-BETA.2"], ["fix: a"]), "v1.1.1-BETA.1");
});

test("nextBeta with no new commits continues the existing beta line, else throws", () => {
  assert.equal(nextBeta("v1.0.0", ["v1.0.1-BETA.1"], []), "v1.0.1-BETA.2");
  assert.throws(() => nextBeta("v1.0.0", [], []));
});

test("nextStable strips the beta suffix", () => {
  assert.equal(nextStable("v1.1.0-BETA.3"), "v1.1.0");
  assert.throws(() => nextStable("v1.1.0"));
});
