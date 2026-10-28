import test from "node:test";
import assert from "node:assert/strict";
import { buildBody, parseBody, updateBody, releaseNotes, promotedHead, setPromotedHead } from "../lib/pr-body.mjs";

const sha = (c) => c.repeat(40);
const id = (c) => c.repeat(12); // the short SHA kept in the seen marker

test("buildBody and parseBody round-trip", () => {
  const body = buildBody({ changelog: "### Fixes\n- a (aaaaaaa)", botEnabled: true, seen: [sha("a")] });
  assert.match(body, /- \[x\] <!-- changelog-bot --> Le bot met à jour le changelog/);
  assert.deepEqual(parseBody(body), {
    changelog: "### Fixes\n- a (aaaaaaa)",
    botEnabled: true,
    seen: [id("a")],
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
  assert.deepEqual(parsed.seen, [id("1"), id("2"), id("3")]);
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
  assert.deepEqual(parseBody(out).seen, [id("a")]);
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

test("promotedHead_marker_isWrittenAndReplaced", () => {
  const body = buildBody({ changelog: "", botEnabled: false, seen: [], promotedHead: sha("a") });
  assert.equal(promotedHead(body), sha("a"));
  const moved = setPromotedHead(body, sha("b"));
  assert.equal(promotedHead(moved), sha("b"));
  assert.equal(moved.match(/promoted-head/g).length, 1);
  assert.equal(promotedHead(buildBody({ changelog: "", botEnabled: true, seen: [] })), null);
});

// n distinct commits with realistic conventional subjects.
const many = (n, from = 0) =>
  Array.from({ length: n }, (_, i) => ({
    sha: (from + i).toString(16).padStart(12, "0").padEnd(40, "f"),
    subject: `${["feat", "fix", "perf", "docs"][i % 4]}(web): change number ${from + i} of the channel page`,
  }));
const entries = (changelog) => changelog.split("\n").filter((line) => line.startsWith("- "));
const fresh = () => buildBody({ changelog: "", botEnabled: true, seen: [] });

test("seen_storesShortShas_andStillMatchesFullOnes", () => {
  const legacy = buildBody({ changelog: "### Fixes\n- a (aaaaaaa)", botEnabled: true, seen: [] }).replace(
    "<!-- changelog:seen  -->",
    `<!-- changelog:seen ${sha("a")} -->`,
  );
  assert.deepEqual(parseBody(legacy).seen, [id("a")], "a full SHA written before is read as its short form");
  assert.equal(updateBody(legacy, [{ sha: sha("a"), subject: "fix: a" }]), legacy, "already seen");
  const out = updateBody(legacy, [{ sha: sha("b"), subject: "fix: b" }]);
  assert.match(out, new RegExp(`<!-- changelog:seen ${id("a")},${id("b")} -->`));
});

for (const n of [1000, 2500]) {
  test(`hugePromotion_${n}Commits_capsTheEntries_andKeepsEverySha`, () => {
    const out = updateBody(fresh(), many(n));
    const { changelog, seen } = parseBody(out);
    assert.equal(entries(changelog).length, 200);
    assert.match(changelog, new RegExp(`\n\n… et ${n - 200} autres commits$`));
    assert.equal(seen.length, n);
    assert.equal(new Set(seen).size, n);
    assert.ok(out.length < 60000, `body is ${out.length} chars`);
  });
}

test("hugePromotion_refresh_countsTheNewCommitsInTheOverflow", () => {
  const first = updateBody(fresh(), many(1000));
  const out = updateBody(first, [...many(1000), ...many(10, 1000)]);
  const { changelog, seen } = parseBody(out);
  assert.equal(entries(changelog).length, 200);
  assert.match(changelog, /\n\n… et 810 autres commits$/);
  assert.equal(changelog.match(/autres commits/g).length, 1);
  assert.equal(seen.length, 1010);
});

test("smallRefresh_fillsTheRoomLeftBeforeOverflowing", () => {
  const first = updateBody(fresh(), many(199));
  const out = updateBody(first, many(3, 199));
  const { changelog } = parseBody(out);
  assert.equal(entries(changelog).length, 200);
  assert.match(changelog, /\n\n… et 2 autres commits$/);
  assert.match(parseBody(updateBody(fresh(), many(201))).changelog, /\n\n… et 1 autre commit$/);
});

test("bodyGuard_keepsTheBodyUnder60000Chars", () => {
  const long = many(300).map((c) => ({ ...c, subject: `feat: ${"x".repeat(400)} ${c.sha}` }));
  const out = updateBody(fresh(), long);
  const { changelog, seen } = parseBody(out);
  assert.ok(out.length < 60000, `body is ${out.length} chars`);
  assert.ok(entries(changelog).length < 200, "entries dropped to fit");
  assert.match(changelog, new RegExp(`… et ${300 - entries(changelog).length} autres commits$`));
  assert.equal(seen.length, 300);
});

test("updateBody groups the deps scope under Dependencies, whatever its type", () => {
  const start = buildBody({ changelog: "### Fixes\n- f (fffffff)\n\n### Other\n- o (ooooooo)", botEnabled: true, seen: [] });
  const out = updateBody(start, [
    { sha: sha("1"), subject: "fix(deps): update dependency foo to v2" },
    { sha: sha("2"), subject: "chore(deps): update redis docker tag to v8" },
  ]);
  assert.equal(
    parseBody(out).changelog,
    "### Fixes\n- f (fffffff)\n\n### Dependencies\n- update dependency foo to v2 (1111111)\n" +
      "- update redis docker tag to v8 (2222222)\n\n### Other\n- o (ooooooo)",
  );
});
