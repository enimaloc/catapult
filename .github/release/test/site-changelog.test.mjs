import test from "node:test";
import assert from "node:assert/strict";
import { siteChangelog } from "../site-changelog.mjs";

const tags = (text) => [...text.matchAll(/^## \[(\S+)\]/gm)].map((m) => m[1]);
const published = { tag: "v1.0.0-BETA.1", body: "### Fixes\n- edited after publication", publishedAt: "2026-10-01T10:00:00Z" };

test("theVersionBeingReleased_comesFirstWithItsNotes", () => {
  const text = siteChangelog({ releases: [published], tag: "v1.0.0-BETA.2", notes: "### Features\n- edited in the PR", repository: "o/r" });
  assert.deepEqual(tags(text), ["v1.0.0-BETA.2", "v1.0.0-BETA.1"]);
  assert.match(text, /edited in the PR/);
  assert.match(text, /edited after publication/);
});

test("anAlreadyPublishedTag_isNotListedTwice", () => {
  const text = siteChangelog({ releases: [published], tag: "v1.0.0-BETA.1", notes: "new", repository: "o/r" });
  assert.deepEqual(tags(text), ["v1.0.0-BETA.1"]);
  assert.match(text, /new/);
});
