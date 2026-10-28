import test from "node:test";
import assert from "node:assert/strict";
import { renderChangelogFile } from "../lib/changelog-file.mjs";

const sectionTags = (text) => [...text.matchAll(/^## \[(\S+)\]/gm)].map((m) => m[1]);

test("versions_newestFirst_stableAfterItsBetas", () => {
  const text = renderChangelogFile([
    { tag: "v1.0.0-BETA.2", date: "2026-10-02", body: "b2" },
    { tag: "v0.0.0", date: "2026-03-21", body: "init" },
    { tag: "v1.0.0", date: "2026-10-05", body: "stable" },
    { tag: "v1.0.0-BETA.10", date: "2026-10-03", body: "b10" },
  ]);
  assert.deepEqual(sectionTags(text), ["v1.0.0", "v1.0.0-BETA.10", "v1.0.0-BETA.2", "v0.0.0"]);
});

test("section_linksTheReleaseAndCarriesItsDate", () => {
  const text = renderChangelogFile([{ tag: "v1.0.0", date: "2026-10-05", body: "### Fixes\n- a fix (abc1234)" }], "o/r");
  assert.match(text, /^## \[v1\.0\.0\]\(https:\/\/github\.com\/o\/r\/releases\/tag\/v1\.0\.0\) - 2026-10-05\n\n### Fixes\n- a fix \(abc1234\)$/m);
  assert.ok(text.startsWith("# Changelog\n"));
  assert.ok(text.endsWith("(abc1234)\n"));
});

test("notesHeadings_nestUnderTheVersion", () => {
  const text = renderChangelogFile([{ tag: "v1.0.0", date: null, body: "## What's Changed\n# Big\n### Fixes\n#hashtag" }]);
  assert.match(text, /^## \[v1\.0\.0\]\([^)]+\)\n\n### What's Changed\n### Big\n### Fixes\n#hashtag$/m);
});

test("nonVersionTags_andEmptyNotes", () => {
  const text = renderChangelogFile([
    { tag: "nightly", date: "2026-10-01", body: "x" },
    { tag: "v1.0.1", date: "2026-10-06", body: "  " },
  ]);
  assert.deepEqual(sectionTags(text), ["v1.0.1"]);
  assert.match(text, /- 2026-10-06\n\n_Aucune note\._\n$/);
});
