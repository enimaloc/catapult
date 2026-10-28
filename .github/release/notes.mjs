// Release notes of a pushed version tag: the (possibly hand-edited) changelog of the merged release
// PR whose merge commit the tag points at; empty when there is none (a tag pushed by hand).
// CLI: node .github/release/notes.mjs --tag v1.0.0 --sha <commit> --out notes.md
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo)

import { writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { parseVersion } from "./lib/version.mjs";
import { releaseNotes } from "./lib/pr-body.mjs";
import { createGitHub } from "./lib/github.mjs";

/**
 * Looks among the recently closed PRs into the tag's base (beta for a pre-release, main for a stable):
 * GET /commits/{sha}/pulls is not usable here, it only lists open PRs for a commit outside `dev`.
 */
export async function notesFor({ gh, tag, sha }) {
  const base = parseVersion(tag).beta === null ? "main" : "beta";
  const pulls = await gh.listPulls({ base, state: "closed" });
  const pull = pulls.find((p) => p.merged && p.mergeCommitSha === sha && p.title === `chore(release): ${tag}`);
  return pull ? releaseNotes(pull.body ?? "") : "";
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({
    options: { tag: { type: "string" }, sha: { type: "string" }, out: { type: "string" } },
  });
  if (!values.tag || !values.sha || !values.out) throw new Error("Usage: notes.mjs --tag <tag> --sha <sha> --out <path>");
  const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "").split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const notes = await notesFor({ gh, tag: values.tag, sha: values.sha });
  writeFileSync(values.out, notes);
  console.log(notes ? `notes taken from the release PR (${notes.length} chars)` : "no release PR: generated notes");
}
