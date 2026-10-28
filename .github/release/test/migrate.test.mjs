import test from "node:test";
import assert from "node:assert/strict";
import { planMigration, runPhase, planHash, createRegistry, parseTagRefs, IMAGES } from "../migrate.mjs";

const INIT = "c".repeat(40);
const sha = (n) => n.toString(16).padStart(40, "0");
const [API, WEB] = IMAGES;

// The 96 real tags, in their creation order (v0.10.0-ALPHA was tagged after v0.1.1-BETA).
const REAL_TAGS = `v0.0.1-ALPHA v0.1.0-ALPHA v0.2.0-ALPHA v0.2.1-ALPHA v0.2.2-ALPHA v0.2.3-ALPHA v0.2.4-ALPHA
v0.2.5-ALPHA v0.2.6-ALPHA v0.2.7-ALPHA v0.3.0-ALPHA v0.4.0-ALPHA v0.4.1-ALPHA v0.4.2-ALPHA v0.4.3-ALPHA
v0.4.4-ALPHA v0.4.5-ALPHA v0.4.6-ALPHA v0.4.7-ALPHA v0.5.0-ALPHA v0.5.1-ALPHA v0.5.2-ALPHA v0.5.3-ALPHA
v0.5.4-ALPHA v0.5.5-ALPHA v0.5.6-ALPHA v0.5.7-ALPHA v0.5.8-ALPHA v0.5.9-ALPHA v0.5.10-ALPHA v0.5.11-ALPHA
v0.5.12-ALPHA v0.5.13-ALPHA v0.5.14-ALPHA v0.6.0-ALPHA v0.6.1-ALPHA v0.7.0-ALPHA v0.7.1-ALPHA v0.7.2-ALPHA
v0.7.3-ALPHA v0.7.4-ALPHA v0.8.0-ALPHA v0.8.1-ALPHA v0.8.2-ALPHA v0.9.0-ALPHA v0.9.1-ALPHA v0.9.2-ALPHA
v0.9.3-ALPHA v0.9.4-ALPHA v0.9.5-ALPHA v0.9.6-ALPHA v0.10.1-ALPHA v0.10.2-ALPHA v0.10.3-ALPHA v0.10.4-ALPHA
v0.10.5-ALPHA v0.10.6-ALPHA v0.10.7-ALPHA v0.10.8-ALPHA v0.10.9-ALPHA v0.10.10-ALPHA v0.10.11-ALPHA
v0.10.12-ALPHA v0.11.0-ALPHA v0.11.2-ALPHA v0.1.0-BETA v0.1.1-BETA v0.10.0-ALPHA v0.1.2-BETA v0.1.3-BETA
v0.1.4-BETA v0.1.5-BETA v0.1.6-BETA v0.1.7-BETA v0.1.8-BETA v0.1.9-BETA v0.1.10-BETA v0.1.11-BETA
v0.1.12-BETA v0.2.0-BETA v0.2.1-BETA v0.2.2-BETA v0.2.3-BETA v0.2.4-BETA v0.2.5-BETA v0.2.6-BETA v0.2.7-BETA
v0.2.8-BETA v0.2.9-BETA v0.3.1-BETA v0.3.2-BETA v0.3.3-BETA v0.3.4-BETA v0.3.5-BETA v0.3.6-BETA
v0.3.7-BETA`.split(/\s+/);

const dated = (names) =>
  names.map((name, i) => ({ name, sha: sha(i + 1), created: new Date(Date.UTC(2026, 5, 1) + i * 3600e3).toISOString() }));

// Linear history: every commit descends from every earlier one.
const linear = { isAncestor: () => true };

// Three old tags; the last two share nothing with the new names.
const SMALL = planMigration(dated(["v0.0.1-ALPHA", "v0.1.0-BETA", "v0.1.1-BETA"]), INIT, linear);

// In-memory GitHub: tags/branches map name -> commit SHA, releases map tag -> release.
function fakeGitHub({ tags = {}, releases = {}, branches = {}, workflows = [], failDisable = [], compare } = {}) {
  const writes = [];
  const compared = [];
  let nextId = 100;
  return {
    writes,
    compared,
    tags,
    releases,
    workflows,
    async getTag(name) {
      return tags[name] ?? null;
    },
    async createTag({ tag, sha: target, message }) {
      writes.push(["createTag", tag, target, message]);
      tags[tag] = target;
    },
    async deleteTag(name) {
      writes.push(["deleteTag", name]);
      delete tags[name];
    },
    async compareCommits(base, head) {
      compared.push([base, head]);
      if (compare) return compare(base, head);
      return [{ sha: head, subject: `feat: work up to ${head.slice(-2)}`, message: "", author: null, parents: [base] }];
    },
    async getReleaseByTag(tag) {
      return releases[tag] ?? null;
    },
    async createRelease(fields) {
      writes.push(["createRelease", fields]);
      releases[fields.tag] = { id: nextId++, tag: fields.tag, prerelease: fields.prerelease, url: "u" };
    },
    async deleteRelease(id) {
      writes.push(["deleteRelease", id]);
      for (const [tag, release] of Object.entries(releases)) if (release.id === id) delete releases[tag];
    },
    async getBranch(name) {
      return branches[name] ?? null;
    },
    async deleteBranch(name) {
      writes.push(["deleteBranch", name]);
      delete branches[name];
    },
    async listWorkflows() {
      return workflows.map((w) => ({ ...w }));
    },
    async disableWorkflow(id) {
      if (failDisable.includes(id)) throw new Error(`PUT disable ${id}: 403`);
      writes.push(["disableWorkflow", id]);
      workflows.find((w) => w.id === id).state = "disabled_manually";
    },
    async enableWorkflow(id) {
      writes.push(["enableWorkflow", id]);
      workflows.find((w) => w.id === id).state = "active";
    },
  };
}

// In-memory registry: image references -> digest. It only offers reads and tag copies.
function fakeRegistry(images = {}) {
  const writes = [];
  return {
    writes,
    images,
    async digest(ref) {
      return images[ref] ?? null;
    },
    async tag(source, target) {
      writes.push(["tag", source, target]);
      images[target] = images[source];
    },
  };
}

function memoryStore(initial = {}) {
  let state = structuredClone(initial);
  return {
    read: () => structuredClone(state),
    write: (next) => {
      state = structuredClone(next);
    },
  };
}

const oldImages = (plan) =>
  Object.fromEntries(
    plan
      .filter((e) => e.name)
      .flatMap((e, i) => IMAGES.map((image) => [`${image}:${e.name}`, `sha256:${image.slice(-3)}${i}`])),
  );

const quiet = () => {};
const run = (phase, plan, deps) => runPhase(phase, plan, { log: quiet, ...deps });

test("plan_ordersByCreation_andEndsAtBeta96", () => {
  const tags = dated(REAL_TAGS);
  const shuffled = [...tags].reverse().concat({ name: "v1.0.0-BETA.3", sha: sha(999), created: "2026-10-28T00:00:00Z" });
  const plan = planMigration(shuffled, INIT, linear);

  assert.equal(plan.length, 97);
  assert.deepEqual(plan[0], { name: null, newName: "v0.0.0", sha: INIT, base: null, nonLinear: false });
  assert.deepEqual(plan[1], {
    name: "v0.0.1-ALPHA",
    newName: "v1.0.0-BETA.1",
    sha: tags[0].sha,
    base: "v0.0.0",
    nonLinear: false,
  });
  assert.deepEqual(plan[96], {
    name: "v0.3.7-BETA",
    newName: "v1.0.0-BETA.96",
    sha: tags[95].sha,
    base: "v1.0.0-BETA.95",
    nonLinear: false,
  });
  assert.deepEqual(
    plan.slice(1).map((e) => e.name),
    REAL_TAGS,
  );
  assert.equal(plan.find((e) => e.name === "v0.10.0-ALPHA").newName, "v1.0.0-BETA.68");
});

test("plan_sameCreationSecond_keepsTheVersionOrder", () => {
  const at = "2026-08-08T02:55:14+02:00";
  const plan = planMigration(
    [
      { name: "v0.1.8-BETA", sha: sha(8), created: at },
      { name: "v0.1.10-BETA", sha: sha(10), created: at },
      { name: "v0.1.7-BETA", sha: sha(7), created: at },
    ],
    INIT,
    linear,
  );
  assert.deepEqual(
    plan.map((e) => e.name),
    [null, "v0.1.7-BETA", "v0.1.8-BETA", "v0.1.10-BETA"],
  );
});

test("plan_changelogBase_isTheLatestEarlierAncestor_elseTheInitialCommit", () => {
  // 1 <- 2 <- 4 on one line, 3 on a side line from 1, 5 unrelated to all of them.
  const ancestors = { [sha(2)]: [sha(1)], [sha(3)]: [sha(1)], [sha(4)]: [sha(1), sha(2)], [sha(5)]: [] };
  const isAncestor = (a, b) => a === INIT || (ancestors[b] ?? []).includes(a);
  const plan = planMigration(dated(["v0.1.0-BETA", "v0.2.0-BETA", "v0.3.0-BETA", "v0.4.0-BETA", "v0.5.0-BETA"]), INIT, {
    isAncestor,
  });

  assert.deepEqual(
    plan.map((e) => [e.newName, e.base, e.nonLinear]),
    [
      ["v0.0.0", null, false],
      ["v1.0.0-BETA.1", "v0.0.0", false],
      ["v1.0.0-BETA.2", "v1.0.0-BETA.1", false],
      ["v1.0.0-BETA.3", "v1.0.0-BETA.1", false],
      ["v1.0.0-BETA.4", "v1.0.0-BETA.2", false],
      ["v1.0.0-BETA.5", "v0.0.0", true],
    ],
  );
  assert.notEqual(planHash(plan), planHash(planMigration(dated(plan.slice(1).map((e) => e.name)), INIT, linear)));
});

test("create_releaseBody_comparesWithTheBase_andFlagsNonLinearHistory", async () => {
  const plan = [
    { name: null, newName: "v0.0.0", sha: INIT, base: null, nonLinear: false },
    { name: "v0.1.0-BETA", newName: "v1.0.0-BETA.1", sha: sha(1), base: "v0.0.0", nonLinear: false },
    { name: "v0.2.0-BETA", newName: "v1.0.0-BETA.2", sha: sha(2), base: "v0.0.0", nonLinear: true },
  ];
  const gh = fakeGitHub();

  await run("create", plan, { gh, registry: fakeRegistry(oldImages(plan)), store: memoryStore(), dryRun: false });

  assert.deepEqual(gh.compared, [
    [INIT, sha(1)],
    [INIT, sha(2)],
  ]);
  const bodies = gh.writes.filter((w) => w[0] === "createRelease").map((w) => w[1].body);
  assert.doesNotMatch(bodies[1], /Historique non linéaire/);
  assert.match(bodies[2], /Historique non linéaire : comparé au commit initial\./);
});

test("create_releaseBody_capsTheChangelogAt200Entries", async () => {
  const many = Array.from({ length: 205 }, (_, i) => ({ sha: sha(i + 1), subject: `fix: n${i}`, message: "", author: null, parents: [] }));
  const gh = fakeGitHub({ compare: () => many });

  await run("create", SMALL.slice(0, 2), { gh, registry: fakeRegistry(oldImages(SMALL)), store: memoryStore(), dryRun: false });

  const body = gh.writes.find((w) => w[0] === "createRelease" && w[1].tag === "v1.0.0-BETA.1")[1].body;
  assert.equal((body.match(/^- /gm) ?? []).length, 200);
  assert.match(body, /… et 5 autres commits/);
});

test("create_disableRecordedNowhere_namesTheWorkflowToReenableByHand", async () => {
  const gh = fakeGitHub({ workflows: [{ id: 7, name: "Build", state: "active" }] });
  const store = {
    read: () => ({}),
    write: () => {
      throw new Error("EACCES");
    },
  };

  await assert.rejects(
    run("create", SMALL, { gh, registry: fakeRegistry(oldImages(SMALL)), store, dryRun: false }),
    /Build \(7\).*à la main.*gh workflow enable 7/s,
  );
  assert.ok(!gh.writes.some((w) => w[0] === "createTag"));
});

test("create_dryRun_listsEverything_withoutWriting", async () => {
  const plan = planMigration(dated(REAL_TAGS), INIT, linear);
  const gh = fakeGitHub({ workflows: [{ id: 1, name: "Build", state: "active" }] });
  const registry = fakeRegistry(oldImages(plan));
  const store = memoryStore();
  const lines = [];

  const result = await runPhase("create", plan, { gh, registry, store, dryRun: true, log: (l) => lines.push(l) });

  assert.deepEqual(gh.writes, []);
  assert.deepEqual(registry.writes, []);
  assert.deepEqual(store.read(), {});
  assert.deepEqual(result.counts, { tags: 97, releases: 97, images: 194, noImage: 0 });
  assert.ok(lines.some((l) => l.includes("[simulation] désactivation du workflow Build (1)")));
});

test("create_disablesEveryActiveWorkflow_beforeAnyTag_andRecordsThem", async () => {
  const gh = fakeGitHub({
    workflows: [
      { id: 1, name: "Build", state: "active" },
      { id: 2, name: "Old", state: "disabled_manually" },
      { id: 3, name: "Release", state: "active" },
    ],
  });
  const store = memoryStore();

  await run("create", SMALL, { gh, registry: fakeRegistry(oldImages(SMALL)), store, dryRun: false });

  assert.deepEqual(gh.writes.slice(0, 2), [
    ["disableWorkflow", 1],
    ["disableWorkflow", 3],
  ]);
  assert.deepEqual(store.read().disabledWorkflows, [1, 3]);
});

test("create_refusesToTag_whenAWorkflowCouldNotBeDisabled", async () => {
  const gh = fakeGitHub({
    workflows: [
      { id: 1, name: "Build", state: "active" },
      { id: 3, name: "Release", state: "active" },
    ],
    failDisable: [3],
  });
  const store = memoryStore();

  await assert.rejects(
    run("create", SMALL, { gh, registry: fakeRegistry(oldImages(SMALL)), store, dryRun: false }),
    /Release/,
  );
  assert.deepEqual(gh.writes, [["disableWorkflow", 1]]);
  assert.deepEqual(store.read().disabledWorkflows, [1]);
});

test("create_tagsReleasesAndImages_asPlanned", async () => {
  const gh = fakeGitHub();
  const registry = fakeRegistry(oldImages(SMALL));

  await run("create", SMALL, { gh, registry, store: memoryStore(), dryRun: false });

  assert.equal(gh.tags["v0.0.0"], INIT);
  assert.equal(gh.tags["v1.0.0-BETA.3"], SMALL[3].sha);
  const tagMessages = gh.writes.filter((w) => w[0] === "createTag").map((w) => w[3]);
  assert.equal(tagMessages[2], "Release v1.0.0-BETA.2 (formerly v0.1.0-BETA)");

  const releases = gh.writes.filter((w) => w[0] === "createRelease").map((w) => w[1]);
  assert.deepEqual(
    releases.map((r) => [r.tag, r.prerelease, r.makeLatest]),
    [
      ["v0.0.0", false, true],
      ["v1.0.0-BETA.1", true, false],
      ["v1.0.0-BETA.2", true, false],
      ["v1.0.0-BETA.3", true, false],
    ],
  );
  assert.match(releases[2].body, /### Features\n- work up to 02 \(0000000\)/);
  assert.match(releases[2].body, /Anciennement `v0\.1\.0-BETA`/);

  assert.deepEqual(registry.writes, [
    ["tag", `${API}:v0.0.1-ALPHA`, `${API}:1.0.0-BETA.1`],
    ["tag", `${WEB}:v0.0.1-ALPHA`, `${WEB}:1.0.0-BETA.1`],
    ["tag", `${API}:v0.1.0-BETA`, `${API}:1.0.0-BETA.2`],
    ["tag", `${WEB}:v0.1.0-BETA`, `${WEB}:1.0.0-BETA.2`],
    ["tag", `${API}:v0.1.1-BETA`, `${API}:1.0.0-BETA.3`],
    ["tag", `${WEB}:v0.1.1-BETA`, `${WEB}:1.0.0-BETA.3`],
    ["tag", `${API}:v0.1.1-BETA`, `${API}:beta`],
    ["tag", `${WEB}:v0.1.1-BETA`, `${WEB}:beta`],
  ]);
  assert.ok(!registry.writes.some((w) => w[2].endsWith(":latest")));
});

test("create_oldImageMissing_recordsNoImage_andContinues", async () => {
  const images = oldImages(SMALL);
  delete images[`${API}:v0.0.1-ALPHA`];
  delete images[`${WEB}:v0.0.1-ALPHA`];
  const registry = fakeRegistry(images);

  const result = await run("create", SMALL, { gh: fakeGitHub(), registry, store: memoryStore(), dryRun: false });

  assert.equal(result.counts.noImage, 2);
  assert.equal(registry.writes.length, 6);
});

test("create_existingTagOnAnotherCommit_isAnError", async () => {
  const gh = fakeGitHub({ tags: { "v1.0.0-BETA.1": sha(777) } });
  await assert.rejects(
    run("create", SMALL, { gh, registry: fakeRegistry(oldImages(SMALL)), store: memoryStore(), dryRun: false }),
    /v1\.0\.0-BETA\.1/,
  );
});

test("rerunAfterInterruption_skipsExisting", async () => {
  const images = oldImages(SMALL);
  // An earlier run stopped after the second entry: its tags, releases and images exist.
  const gh = fakeGitHub({
    tags: { "v0.0.0": INIT, "v1.0.0-BETA.1": SMALL[1].sha },
    releases: { "v0.0.0": { id: 1, tag: "v0.0.0", prerelease: false }, "v1.0.0-BETA.1": { id: 2, tag: "v1.0.0-BETA.1", prerelease: true } },
  });
  for (const image of IMAGES) images[`${image}:1.0.0-BETA.1`] = images[`${image}:v0.0.1-ALPHA`];
  const registry = fakeRegistry(images);
  const store = memoryStore();

  await run("create", SMALL, { gh, registry, store, dryRun: false });

  const created = gh.writes.filter((w) => w[0] !== "disableWorkflow").map((w) => w[1].tag ?? w[1]);
  assert.deepEqual(created, ["v1.0.0-BETA.2", "v1.0.0-BETA.2", "v1.0.0-BETA.3", "v1.0.0-BETA.3"]);
  assert.equal(registry.writes.length, 6);

  gh.writes.length = 0;
  registry.writes.length = 0;
  await run("create", SMALL, { gh, registry, store, dryRun: false });
  assert.deepEqual(gh.writes, []);
  assert.deepEqual(registry.writes, []);
});

async function created(plan = SMALL, workflows = [{ id: 1, name: "Build", state: "active" }]) {
  const gh = fakeGitHub({
    workflows,
    branches: { "v/v0.1.0-BETA": plan[2].sha },
    releases: { "v0.1.0-BETA": { id: 50, tag: "v0.1.0-BETA", prerelease: true } },
    tags: Object.fromEntries(plan.filter((e) => e.name).map((e) => [e.name, e.sha])),
  });
  const registry = fakeRegistry(oldImages(plan));
  const store = memoryStore();
  await run("create", plan, { gh, registry, store, dryRun: false });
  gh.writes.length = 0;
  registry.writes.length = 0;
  return { gh, registry, store };
}

test("verify_allPresent_passes_recordsIt_andRestoresTheWorkflows", async () => {
  const { gh, registry, store } = await created();

  const result = await run("verify", SMALL, { gh, registry, store, dryRun: false });

  assert.equal(result.passed, true);
  assert.match(result.report, /v1\.0\.0-BETA\.3/);
  assert.deepEqual(store.read().verify.planHash, planHash(SMALL));
  assert.equal(store.read().verify.passed, true);
  assert.deepEqual(gh.writes, [["enableWorkflow", 1]]);
  assert.deepEqual(store.read().disabledWorkflows, []);
});

test("verify_imageOnAnotherDigest_fails_andKeepsWorkflowsDisabled", async () => {
  const { gh, registry, store } = await created();
  registry.images[`${WEB}:1.0.0-BETA.2`] = "sha256:other";

  const result = await run("verify", SMALL, { gh, registry, store, dryRun: false });

  assert.equal(result.passed, false);
  assert.equal(store.read().verify.passed, false);
  assert.deepEqual(gh.writes, []);
  assert.deepEqual(store.read().disabledWorkflows, [1]);
});

test("verify_missingRelease_fails", async () => {
  const { gh, registry, store } = await created();
  delete gh.releases["v1.0.0-BETA.2"];
  assert.equal((await run("verify", SMALL, { gh, registry, store, dryRun: false })).passed, false);
});

test("restore_reenablesExactlyTheRecordedWorkflows", async () => {
  const gh = fakeGitHub({
    workflows: [
      { id: 1, name: "Build", state: "disabled_manually" },
      { id: 2, name: "Old", state: "disabled_manually" },
    ],
  });
  const store = memoryStore({ disabledWorkflows: [1] });

  await run("restore", SMALL, { gh, store, dryRun: false });

  assert.deepEqual(gh.writes, [["enableWorkflow", 1]]);
  assert.deepEqual(store.read().disabledWorkflows, []);
});

test("delete_refusesWithoutSuccessfulVerify", async () => {
  const { gh, registry, store } = await created();
  for (const state of [{}, { verify: { planHash: planHash(SMALL), passed: false } }, { verify: { planHash: "x", passed: true } }]) {
    store.write(state);
    for (const dryRun of [true, false]) {
      await assert.rejects(run("delete", SMALL, { gh, registry, store, dryRun }), /vérification/);
    }
  }
  assert.deepEqual(gh.writes, []);
});

async function verified() {
  const setup = await created();
  await run("verify", SMALL, { ...setup, dryRun: false });
  setup.gh.writes.length = 0;
  return setup;
}

test("delete_disablesTheWorkflowsFirst_andRestoresThemAfterSuccess", async () => {
  const { gh, registry, store } = await verified();

  await run("delete", SMALL, { gh, registry, store, dryRun: false });

  assert.deepEqual(gh.writes[0], ["disableWorkflow", 1]);
  assert.deepEqual(gh.writes.at(-1), ["enableWorkflow", 1]);
  assert.deepEqual(store.read().disabledWorkflows, []);
});

test("delete_refusesToDelete_whenAWorkflowCouldNotBeDisabled", async () => {
  const { gh, registry, store } = await verified();
  gh.workflows.push({ id: 2, name: "Release", state: "active" });
  const failing = { ...gh, disableWorkflow: async (id) => (id === 2 ? Promise.reject(new Error("403")) : gh.disableWorkflow(id)) };

  await assert.rejects(run("delete", SMALL, { gh: failing, registry, store, dryRun: false }), /Suppression refusée.*Release/s);
  assert.deepEqual(gh.writes, [["disableWorkflow", 1]]);
  assert.deepEqual(store.read().disabledWorkflows, [1]);
});

test("delete_midwayFailure_leavesTheWorkflowsDisabled", async () => {
  const { gh, registry, store } = await verified();
  const failing = { ...gh, deleteTag: async () => Promise.reject(new Error("500")) };

  await assert.rejects(run("delete", SMALL, { gh: failing, registry, store, dryRun: false }), /500/);
  assert.ok(!gh.writes.some((w) => w[0] === "enableWorkflow"));
  assert.deepEqual(store.read().disabledWorkflows, [1]);
});

test("delete_newTagMoved_stopsBeforeDeletingIt", async () => {
  const { gh, registry, store } = await verified();
  gh.tags["v1.0.0-BETA.2"] = sha(555);

  await assert.rejects(run("delete", SMALL, { gh, registry, store, dryRun: false }), /Suppression interrompue.*BETA\.2/);
  assert.ok(gh.tags["v0.1.0-BETA"]);
  assert.ok(gh.releases["v0.1.0-BETA"]);
});

test("delete_newReleaseMissing_keepsTheOldRelease", async () => {
  const { gh, registry, store } = await verified();
  delete gh.releases["v1.0.0-BETA.2"];

  await assert.rejects(run("delete", SMALL, { gh, registry, store, dryRun: false }), /Suppression interrompue.*release/);
  assert.ok(gh.releases["v0.1.0-BETA"]);
});

test("delete_afterVerify_removesOldTagsReleasesAndBranches", async () => {
  const { gh, registry, store } = await created();
  await run("verify", SMALL, { gh, registry, store, dryRun: false });
  gh.writes.length = 0;

  await run("delete", SMALL, { gh, registry, store, dryRun: false });

  assert.deepEqual(gh.writes.filter((w) => !w[0].endsWith("Workflow")), [
    ["deleteTag", "v0.0.1-ALPHA"],
    ["deleteRelease", 50],
    ["deleteTag", "v0.1.0-BETA"],
    ["deleteBranch", "v/v0.1.0-BETA"],
    ["deleteTag", "v0.1.1-BETA"],
  ]);
  assert.ok(gh.tags["v1.0.0-BETA.2"]);
});

test("ghcr_neverDeletesADigest", async () => {
  const { gh, registry, store } = await created();
  await run("verify", SMALL, { gh, registry, store, dryRun: false });
  const images = { ...registry.images };

  const result = await run("delete", SMALL, { gh, registry, store, dryRun: false });

  assert.deepEqual(registry.writes, []);
  assert.deepEqual(registry.images, images);
  assert.match(result.report, /conserv/);
  for (const name of ["v0.0.1-ALPHA", "v0.1.1-BETA"]) assert.ok(result.report.includes(`${API}:${name}`));
});

test("registry_usesBuildxImagetools_andTreatsNotFoundAsMissing", async () => {
  const calls = [];
  const exec = async (file, args) => {
    calls.push([file, ...args]);
    if (args.includes("inspect") && args.includes(`${API}:gone`)) {
      throw Object.assign(new Error("exit 1"), { stderr: `ERROR: ${API}:gone: not found` });
    }
    return { stdout: JSON.stringify({ mediaType: "x", digest: "sha256:abc", size: 1 }) };
  };
  const registry = createRegistry({ exec });

  assert.equal(await registry.digest(`${API}:v1`), "sha256:abc");
  assert.equal(await registry.digest(`${API}:gone`), null);
  await registry.tag(`${API}:v1`, `${API}:1.0.0-BETA.1`);

  assert.deepEqual(calls, [
    ["docker", "buildx", "imagetools", "inspect", `${API}:v1`, "--format", "{{json .Manifest}}"],
    ["docker", "buildx", "imagetools", "inspect", `${API}:gone`, "--format", "{{json .Manifest}}"],
    ["docker", "buildx", "imagetools", "create", "-t", `${API}:1.0.0-BETA.1`, `${API}:v1`],
  ]);
});

test("registry_otherInspectError_isThrown", async () => {
  const exec = async () => {
    throw Object.assign(new Error("exit 1"), { stderr: "denied: unauthorized" });
  };
  await assert.rejects(createRegistry({ exec }).digest(`${API}:v1`), /unauthorized/);
});

test("parseTagRefs_dereferencesAnnotatedTags", () => {
  const output = `v0.0.1-ALPHA\t${sha(1)}\t\t2026-06-02T11:14:43+02:00\nv0.1.0-ALPHA\t${sha(2)}\t${sha(3)}\t2026-06-02T14:18:56+02:00\n`;
  assert.deepEqual(parseTagRefs(output), [
    { name: "v0.0.1-ALPHA", sha: sha(1), created: "2026-06-02T11:14:43+02:00" },
    { name: "v0.1.0-ALPHA", sha: sha(3), created: "2026-06-02T14:18:56+02:00" },
  ]);
});
