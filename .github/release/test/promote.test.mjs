import test from "node:test";
import assert from "node:assert/strict";
import { promote } from "../promote.mjs";
import { buildBody, parseBody } from "../lib/pr-body.mjs";

const APP = "catapult-release";
const sha = (c) => c.repeat(40);
const commit = (c, subject, login = "enimaloc") => ({ sha: sha(c), subject, message: subject, author: login });

// In-memory GitHub: branches hold the commits not yet on their promotion target.
function fakeGitHub({ tags = ["v0.0.0", "v1.0.0-BETA.96"], ahead = {}, pulls = [] } = {}) {
  const calls = [];
  const body = buildBody({ changelog: "", botEnabled: true, seen: [] });
  const open = (p) => ({ state: "open", draft: true, autoMerge: false, body, ...p });
  const state = { pulls: pulls.map(open), next: 100 };
  const gh = {
    calls,
    state,
    async listTags() {
      return tags;
    },
    async compareCommits(base, head) {
      return ahead[`${base}...${head}`] ?? [];
    },
    async listPulls({ base, head }) {
      return state.pulls.filter((p) => p.state === "open" && p.base === base && p.head === head);
    },
    async createPull(pull) {
      calls.push(["createPull", pull]);
      const created = { number: state.next++, state: "open", autoMerge: false, ...pull };
      state.pulls.push(created);
      return created;
    },
    async updatePull(number, fields) {
      calls.push(["updatePull", number, fields]);
      Object.assign(state.pulls.find((p) => p.number === number), fields);
    },
    async convertToDraft(pull) {
      calls.push(["convertToDraft", pull.number]);
      pull.draft = true;
    },
    async disableAutoMerge(pull) {
      calls.push(["disableAutoMerge", pull.number]);
      pull.autoMerge = false;
    },
    async comment(number, body) {
      calls.push(["comment", number, body]);
    },
  };
  return gh;
}

const run = (gh, source = "dev", target = "beta") =>
  promote({ gh, source, target, owner: "enimaloc", repo: "catapult", appSlug: APP });

test("firstCommitAfterPromotion_opensDraft", async () => {
  const gh = fakeGitHub({ ahead: { "beta...dev": [commit("a", "feat(web): dark mode")] } });
  const result = await run(gh);
  assert.deepEqual(result, { action: "created", number: 100 });
  const [[name, pull]] = gh.calls;
  assert.equal(name, "createPull");
  assert.equal(pull.title, "chore(release): v1.0.0-BETA.97");
  assert.equal(pull.base, "beta");
  assert.equal(pull.head, "dev");
  assert.equal(pull.draft, true);
  assert.deepEqual(pull.labels, ["release"]);
  assert.deepEqual(parseBody(pull.body), {
    changelog: "### Features\n- **web:** dark mode (aaaaaaa)",
    botEnabled: true,
    seen: [sha("a")],
  });
});

test("nextCommit_recomputesTitle", async () => {
  const gh = fakeGitHub({
    tags: ["v0.0.0", "v0.1.0", "v0.1.1-BETA.1", "not-a-version"],
    ahead: { "beta...dev": [commit("a", "fix: one")] },
  });
  await run(gh);
  assert.equal(gh.state.pulls[0].title, "chore(release): v0.1.1-BETA.2");

  gh.state.pulls[0].body = gh.state.pulls[0].body.replace("- one (aaaaaaa)", "- one, by hand");
  gh.calls.length = 0;
  gh.compareCommits = async () => [commit("a", "fix: one"), commit("b", "feat: two")];
  const result = await run(gh);
  assert.deepEqual(result, { action: "updated", number: 100 });
  const pull = gh.state.pulls[0];
  assert.equal(pull.title, "chore(release): v0.2.0-BETA.1");
  assert.equal(parseBody(pull.body).changelog, "### Features\n- two (bbbbbbb)\n\n### Fixes\n- one, by hand");
  assert.deepEqual(parseBody(pull.body).seen, [sha("a"), sha("b")]);
  assert.deepEqual(gh.calls.map(([name]) => name), ["updatePull"], "a draft PR stays a draft, no comment");
});

test("readyPr_isPutBackToDraft_andAutoMergeDisabled", async () => {
  const gh = fakeGitHub({
    ahead: { "beta...dev": [commit("a", "fix: one"), commit("b", "fix: two")] },
    pulls: [{ number: 7, base: "beta", head: "dev", draft: false, autoMerge: true, title: "x" }],
  });
  const result = await run(gh);
  assert.deepEqual(result, { action: "updated", number: 7 });
  assert.deepEqual(gh.calls.map(([name]) => name), ["updatePull", "disableAutoMerge", "convertToDraft", "comment"]);
  const [, number, text] = gh.calls[3];
  assert.equal(number, 7);
  assert.match(text, /draft/);
  assert.match(text, /auto-merge/);
});

test("readyPr_withoutAutoMerge_isOnlyPutBackToDraft", async () => {
  const gh = fakeGitHub({
    ahead: { "beta...dev": [commit("a", "fix: one")] },
    pulls: [{ number: 7, base: "beta", head: "dev", draft: false, autoMerge: false, title: "x" }],
  });
  await run(gh);
  assert.deepEqual(gh.calls.map(([name]) => name), ["updatePull", "convertToDraft", "comment"]);
});

test("unchangedPr_isLeftAlone", async () => {
  const gh = fakeGitHub({ ahead: { "beta...dev": [commit("a", "fix: one")] } });
  await run(gh);
  gh.state.pulls[0].draft = false;
  gh.calls.length = 0;
  assert.deepEqual(await run(gh), { action: "none", number: 100 });
  assert.deepEqual(gh.calls, []);
});

test("nothingToPromote_closesEmptyPr", async () => {
  const empty = fakeGitHub();
  assert.deepEqual(await run(empty), { action: "none" });
  assert.deepEqual(empty.calls, []);

  const stale = fakeGitHub({ pulls: [{ number: 7, base: "beta", head: "dev", title: "x" }] });
  assert.deepEqual(await run(stale), { action: "closed", number: 7 });
  assert.deepEqual(stale.calls, [["updatePull", 7, { state: "closed" }]]);
});

test("onePrPerPair", async () => {
  const gh = fakeGitHub({
    ahead: { "beta...dev": [commit("a", "fix: one")], "main...beta": [commit("c", "fix: three")] },
    pulls: [{ number: 7, base: "beta", head: "dev", title: "x" }],
  });
  assert.deepEqual(await run(gh), { action: "updated", number: 7 });
  assert.deepEqual(await run(gh), { action: "none", number: 7 });
  assert.deepEqual(await run(gh, "beta", "main"), { action: "created", number: 100 });
  assert.equal(gh.calls.filter(([name]) => name === "createPull").length, 1);
  assert.equal(gh.state.pulls.filter((p) => p.base === "beta" && p.head === "dev").length, 1);
});

test("betaToMain_usesTheStableOfTheLatestBeta", async () => {
  const gh = fakeGitHub({
    tags: ["v0.0.0", "v1.0.0-BETA.96", "v1.0.0-BETA.97", "v0.9.0-BETA.3"],
    ahead: { "main...beta": [commit("a", "feat: x"), commit("b", "chore(release): v1.0.0-BETA.97")] },
  });
  await run(gh, "beta", "main");
  assert.equal(gh.calls[0][1].title, "chore(release): v1.0.0");
});

test("betaToMain_withoutBeta_doesNothing", async () => {
  const gh = fakeGitHub({ tags: ["v1.0.0"], ahead: { "main...beta": [commit("a", "fix: x")] } });
  assert.deepEqual(await run(gh, "beta", "main"), { action: "none" });
  assert.deepEqual(gh.calls, []);
});

test("botBumpCommit_isIgnored", async () => {
  const gh = fakeGitHub({
    ahead: { "beta...dev": [commit("a", "fix: one"), commit("b", "chore(release): v1.0.0-BETA.97", `${APP}[bot]`)] },
    pulls: [{ number: 7, base: "beta", head: "dev", draft: false, autoMerge: true, title: "x" }],
  });
  assert.deepEqual(await run(gh), { action: "none", number: 7 });
  assert.deepEqual(gh.calls, []);
});

test("releaseSubjectFromSomeoneElse_isNotTreatedAsTheBump", async () => {
  const gh = fakeGitHub({
    ahead: { "beta...dev": [commit("b", "chore(release): v1.0.0-BETA.97", "other[bot]")] },
  });
  assert.equal((await run(gh)).action, "created");
});

test("disabledBot_keepsTheChangelog_butRecomputesTheTitle", async () => {
  const body = buildBody({ changelog: "hand-written", botEnabled: false, seen: [] });
  const gh = fakeGitHub({
    ahead: { "beta...dev": [commit("a", "fix: one")] },
    pulls: [{ number: 7, base: "beta", head: "dev", title: "x", body }],
  });
  await run(gh);
  assert.deepEqual(gh.calls, [["updatePull", 7, { title: "chore(release): v1.0.0-BETA.97", body }]]);
});
