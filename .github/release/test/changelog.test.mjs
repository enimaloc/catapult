import test from "node:test";
import assert from "node:assert/strict";
import { renderEntries, SECTIONS } from "../lib/changelog.mjs";

const sha = (c) => c.repeat(40);

test("renderEntries groups commits by section with short sha", () => {
  const map = renderEntries([
    { sha: sha("a"), subject: "feat(web): add box art" },
    { sha: sha("b"), subject: "fix: crash on start" },
    { sha: sha("c"), subject: "chore(deps): bump x" },
    { sha: sha("d"), subject: "Merge something odd" },
    { sha: sha("e"), subject: "refactor!: drop api" },
  ]);
  assert.deepEqual(map.get("Features"), ["- **web:** add box art (aaaaaaa)"]);
  assert.deepEqual(map.get("Fixes"), ["- crash on start (bbbbbbb)"]);
  assert.deepEqual(map.get("Refactoring"), ["- drop api (eeeeeee)"]);
  assert.deepEqual(map.get("Other"), ["- **deps:** bump x (ccccccc)", "- Merge something odd (ddddddd)"]);
  assert.deepEqual([...map.keys()], SECTIONS.filter((s) => map.has(s)));
});
