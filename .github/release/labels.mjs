// Applies .github/labels.yml to the repository: creates the missing labels and updates the color and
// description of the existing ones. It never deletes a label, whatever the file says.
// CLI: node .github/release/labels.mjs [--dry-run]   (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo)

import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";

const API = "https://api.github.com";
const FIELDS = new Set(["name", "color", "description"]);

function parseValue(raw) {
  const value = raw.trim();
  if (value.startsWith('"')) return JSON.parse(value);
  if (value.startsWith("'")) return value.slice(1, -1).replaceAll("''", "'");
  return value;
}

/** Parses the flat `- name: … / color: … / description: …` list; comments and blank lines are ignored. */
export function parseLabels(text) {
  const labels = [];
  let current = null;
  for (const [index, line] of text.split("\n").entries()) {
    if (/^\s*(#.*)?$/.test(line)) continue;
    const match = /^(- |  )(\w+):(?: (.*))?$/.exec(line);
    if (!match || !FIELDS.has(match[2])) throw new Error(`labels.yml line ${index + 1}: cannot read "${line}"`);
    if (match[1] === "- ") labels.push((current = {}));
    if (!current) throw new Error(`labels.yml line ${index + 1}: field outside of a list item`);
    current[match[2]] = parseValue(match[3] ?? "");
  }
  return labels.map((label, i) => {
    if (!label.name) throw new Error(`labels.yml entry ${i + 1}: name is required`);
    if (!/^[0-9a-fA-F]{6}$/.test(label.color ?? "")) throw new Error(`label "${label.name}": color must be 6 hex digits`);
    return { name: label.name, color: label.color.toLowerCase(), description: label.description ?? "" };
  });
}

/** What to create and update so that every desired label exists as declared; extra labels are left alone. */
export function diffLabels(desired, existing) {
  const byName = new Map(existing.map((label) => [label.name.toLowerCase(), label])); // GitHub names are case-insensitive
  const create = [];
  const update = [];
  for (const label of desired) {
    const current = byName.get(label.name.toLowerCase());
    if (!current) create.push(label);
    else if (
      current.name !== label.name ||
      current.color.toLowerCase() !== label.color ||
      (current.description ?? "") !== label.description
    ) {
      update.push({ currentName: current.name, ...label });
    }
  }
  return { create, update };
}

/** Applies the diff through the injected client; with dryRun it only returns it. */
export async function applyLabels({ github, labels, dryRun = false }) {
  const diff = diffLabels(labels, await github.listLabels());
  if (!dryRun) {
    for (const { currentName, ...label } of diff.update) await github.updateLabel(currentName, label);
    for (const label of diff.create) await github.createLabel(label);
  }
  return diff;
}

export function createLabelsClient({ token, owner, repo, fetch = globalThis.fetch }) {
  if (!token) throw new Error("A GitHub token is required");
  const base = `${API}/repos/${owner}/${repo}/labels`;
  async function request(method, url, body) {
    const response = await fetch(url, {
      method,
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${token}`,
        "X-GitHub-Api-Version": "2022-11-28",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`${method} ${url}: ${response.status} ${await response.text()}`);
    return response.json();
  }
  return {
    async listLabels() {
      const all = [];
      for (let page = 1; ; page++) {
        const batch = await request("GET", `${base}?per_page=100&page=${page}`);
        all.push(...batch);
        if (batch.length < 100) return all;
      }
    },
    createLabel: (label) => request("POST", base, label),
    updateLabel: (currentName, label) =>
      request("PATCH", `${base}/${encodeURIComponent(currentName)}`, { new_name: label.name, ...label }),
  };
}

async function main() {
  const { values } = parseArgs({ options: { "dry-run": { type: "boolean", default: false } } });
  const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "").split("/");
  if (!owner || !repo) throw new Error("GITHUB_REPOSITORY=owner/repo is required");
  const labels = parseLabels(readFileSync(new URL("../labels.yml", import.meta.url), "utf8"));
  const github = createLabelsClient({ token: process.env.GH_TOKEN, owner, repo });
  const { create, update } = await applyLabels({ github, labels, dryRun: values["dry-run"] });
  const verb = values["dry-run"] ? "would" : "did";
  for (const label of create) console.log(`${verb} create: ${label.name}`);
  for (const label of update) console.log(`${verb} update: ${label.currentName}`);
  console.log(`${create.length} to create, ${update.length} to update, none deleted`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
