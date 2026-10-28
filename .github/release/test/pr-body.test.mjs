import test from "node:test";
import assert from "node:assert/strict";
import { buildBody, parseBody, updateBody, releaseNotes } from "../lib/pr-body.mjs";

const sha = (c) => c.repeat(40);

test("buildBody and parseBody round-trip", () => {
  const body = buildBody({ changelog: "### Fixes\n- a (aaaaaaa)", botEnabled: true, seen: [sha("a")] });
  assert.match(body, /- \[x\] <!-- changelog-bot --> Le bot met à jour le changelog/);
  assert.deepEqual(parseBody(body), {
    changelog: "### Fixes\n- a (aaaaaaa)",
    botEnabled: true,
    seen: [sha("a")],
  });
});

test("toggleStateIsPreserved", () => {
  const off = buildBody({ changelog: "", botEnabled: false, seen: [] });
  assert.match(off, /- \[ \] <!-- changelog-bot -->/);
  assert.equal(parseBody(off).botEnabled, false);
  assert.equal(parseBody(updateBody(off, [])).botEnabled, false);
  const on = updateBody(buildBody({ changelog: "", botEnabled: true, seen: [] }), [
    { sha: sha("a"), subject: "fix: x" },
  ]);
  assert.equal(parseBody(on).botEnabled, true);
});

test("parseBody tolerates outside edits and a missing seen marker", () => {
  const body = [
    "My own intro",
    "- [x] <!-- changelog-bot --> Le bot met à jour le changelog",
    "<!-- changelog:start -->",
    "### Fixes",
    "- a (aaaaaaa)",
    "<!-- changelog:end -->",
    "trailing owner text",
  ].join("\n");
  assert.deepEqual(parseBody(body), {
    changelog: "### Fixes\n- a (aaaaaaa)",
    botEnabled: true,
    seen: [],
  });
});

test("userEditKept_andRemovedEntryNotReadded", () => {
  const start = buildBody({
    changelog: "### Features\n- one (1111111)\n- two (2222222)",
    botEnabled: true,
    seen: [sha("1"), sha("2")],
  });
  // owner rewrites line one and deletes line two
  const edited = start.replace("- one (1111111)", "- ONE rewritten by hand").replace("- two (2222222)\n", "");
  const out = updateBody(edited, [
    { sha: sha("1"), subject: "feat: one" },
    { sha: sha("2"), subject: "feat: two" },
    { sha: sha("3"), subject: "feat: three" },
  ]);
  const parsed = parseBody(out);
  assert.equal(parsed.changelog, "### Features\n- ONE rewritten by hand\n- three (3333333)");
  assert.deepEqual(parsed.seen, [sha("1"), sha("2"), sha("3")]);
});

test("updateBody re-creates a deleted heading at its ordered position", () => {
  const start = buildBody({
    changelog: "### Fixes\n- f (fffffff)\n\n### Other\n- o (ooooooo)",
    botEnabled: true,
    seen: [sha("f"), sha("o")],
  });
  const out = updateBody(start, [
    { sha: sha("1"), subject: "feat: new" },
    { sha: sha("2"), subject: "docs: doc" },
  ]);
  assert.equal(
    parseBody(out).changelog,
    "### Features\n- new (1111111)\n\n### Fixes\n- f (fffffff)\n\n### Documentation\n- doc (2222222)\n\n### Other\n- o (ooooooo)",
  );
});

test("updateBody appends into an existing section and keeps outside text", () => {
  const start = "intro\n" + buildBody({ changelog: "### Fixes\n- f (fffffff)\n\n### Other\n- o (ooooooo)", botEnabled: true, seen: [sha("f")] });
  const out = updateBody(start, [{ sha: sha("9"), subject: "fix(api): g" }]);
  assert.ok(out.startsWith("intro\n"));
  assert.equal(parseBody(out).changelog, "### Fixes\n- f (fffffff)\n- **api:** g (9999999)\n\n### Other\n- o (ooooooo)");
});

test("updateBody adds a seen marker when missing", () => {
  const body = "<!-- changelog:start -->\n<!-- changelog:end -->\n- [x] <!-- changelog-bot --> Le bot met à jour le changelog";
  const out = updateBody(body, [{ sha: sha("a"), subject: "fix: x" }]);
  assert.deepEqual(parseBody(out).seen, [sha("a")]);
  assert.equal(parseBody(out).changelog, "### Fixes\n- x (aaaaaaa)");
});

test("botDisabled_leavesChangelogUntouched", () => {
  const body = buildBody({ changelog: "### Fixes\n- f (fffffff)", botEnabled: false, seen: [sha("f")] });
  assert.equal(updateBody(body, [{ sha: sha("2"), subject: "feat: x" }]), body);
});

test("releaseNotes_returnsEditedText", () => {
  const body = buildBody({ changelog: "### Fixes\n- f (fffffff)", botEnabled: true, seen: [] }).replace(
    "- f (fffffff)",
    "- rewritten\n\nfree text",
  );
  assert.equal(releaseNotes(body), "### Fixes\n- rewritten\n\nfree text");
});
