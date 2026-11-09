import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { applyLabels, diffLabels, parseLabels } from "../labels.mjs";

const YAML = `# comment
- name: bug
  color: d73a4a
  description: "Quelque chose ne marche pas"

- name: "area: api"
  color: 5319e7
  description: catapult-api
`;

test("parseLabels reads the flat list format", () => {
  assert.deepEqual(parseLabels(YAML), [
    { name: "bug", color: "d73a4a", description: "Quelque chose ne marche pas" },
    { name: "area: api", color: "5319e7", description: "catapult-api" },
  ]);
});

test("parseLabels rejects an entry without a name or color", () => {
  assert.throws(() => parseLabels("- color: ffffff\n  description: x\n"), /name/);
  assert.throws(() => parseLabels("- name: x\n  description: y\n"), /color/);
});

test("labels.yml declares the 27 labels, once each, with valid colors", () => {
  const labels = parseLabels(readFileSync(new URL("../../labels.yml", import.meta.url), "utf8"));
  assert.equal(labels.length, 27);
  assert.equal(new Set(labels.map((l) => l.name.toLowerCase())).size, 27);
  for (const l of labels) assert.match(l.color, /^[0-9a-f]{6}$/, l.name);
});

const desired = [
  { name: "bug", color: "d73a4a", description: "Bug" },
  { name: "feature", color: "1d76db", description: "Nouveauté" },
  { name: "release", color: "8b5cf6", description: "Promotion" },
];

test("labels_diff_createsMissing_updatesChanged_neverDeletes", () => {
  const existing = [
    { name: "bug", color: "D73A4A", description: "Bug" }, // same, color case differs
    { name: "Release", color: "ffffff", description: "Promotion" }, // name case differs, color changed
    { name: "legacy", color: "000000", description: "Not in the file" },
  ];
  const diff = diffLabels(desired, existing);
  assert.deepEqual(diff.create, [{ name: "feature", color: "1d76db", description: "Nouveauté" }]);
  assert.deepEqual(diff.update, [
    { currentName: "Release", name: "release", color: "8b5cf6", description: "Promotion" },
  ]);
  assert.equal("delete" in diff, false);
});

test("applyLabels calls create and update only, and reports dry runs without writing", async () => {
  const calls = [];
  const github = {
    async listLabels() {
      return [{ name: "bug", color: "d73a4a", description: "Ancienne" }, { name: "legacy", color: "000000", description: "" }];
    },
    async createLabel(l) {
      calls.push(["create", l.name]);
    },
    async updateLabel(currentName, l) {
      calls.push(["update", currentName, l.description]);
    },
    async deleteLabel() {
      throw new Error("must never delete");
    },
  };
  const dry = await applyLabels({ github, labels: desired, dryRun: true });
  assert.deepEqual(calls, []);
  assert.equal(dry.create.length, 2);
  assert.equal(dry.update.length, 1);

  await applyLabels({ github, labels: desired });
  assert.deepEqual(calls, [["update", "bug", "Bug"], ["create", "feature"], ["create", "release"]]);
});
