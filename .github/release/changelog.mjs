// Regenerates CHANGELOG.md from every published GitHub Release and commits it on the default branch
// (dev), the only branch it is written on: it reaches beta and main through the promotions, so the
// backports never conflict on it. The commit is a release commit (App author, `chore(release): v…`
// subject), never promoted on its own. Run by release.yml once a release is published.
// CLI: node .github/release/changelog.mjs --tag v1.0.0 --branch dev
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo; the version tags fetched locally, for their dates)

import { execFileSync } from "node:child_process";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { CHANGELOG_FILE, renderChangelogFile } from "./lib/changelog-file.mjs";
import { createGitHub } from "./lib/github.mjs";

const ATTEMPTS = 3;

async function currentText(gh, ref) {
  try {
    return (await gh.getFile(CHANGELOG_FILE, ref)).text;
  } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }
}

/**
 * dates: Map tag -> YYYY-MM-DD (the tagged commit's date), the publication date otherwise.
 * -> "unchanged" | "updated"; retries when the branch moves between the read and the commit.
 */
export async function updateChangelog({ gh, tag, branch, dates, repository }) {
  const releases = (await gh.listAllReleases()).map((r) => ({
    tag: r.tag,
    body: r.body,
    date: dates.get(r.tag) ?? r.publishedAt?.slice(0, 10) ?? null,
  }));
  const text = renderChangelogFile(releases, repository);
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    const head = await gh.getBranch(branch);
    if (head === null) throw new Error(`No ${branch} branch to write ${CHANGELOG_FILE} on`);
    if ((await currentText(gh, head)) === text) return "unchanged";
    const message = `chore(release): ${tag} changelog`;
    if ((await gh.commitFile({ branch, parent: head, path: CHANGELOG_FILE, text, message })) !== null) {
      return "updated";
    }
  }
  throw new Error(`${branch} kept moving: ${CHANGELOG_FILE} not written after ${ATTEMPTS} attempts`);
}

/** `git for-each-ref` lines `tag\tpeeled commit date\tcommit date` -> Map tag -> date. */
export function parseTagDates(output) {
  const dates = new Map();
  for (const line of output.split("\n").filter(Boolean)) {
    const [tag, peeled, own] = line.split("\t");
    if (peeled || own) dates.set(tag, peeled || own);
  }
  return dates;
}

export function localTagDates() {
  const format = "%(refname:short)%09%(*committerdate:short)%09%(committerdate:short)";
  return parseTagDates(execFileSync("git", ["for-each-ref", "refs/tags", `--format=${format}`], { encoding: "utf8" }));
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({ options: { tag: { type: "string" }, branch: { type: "string" } } });
  if (!values.tag || !values.branch) throw new Error("Usage: changelog.mjs --tag <tag> --branch <branch>");
  const repository = process.env.GITHUB_REPOSITORY ?? "";
  const [owner, repo] = repository.split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const result = await updateChangelog({ gh, tag: values.tag, branch: values.branch, dates: localTagDates(), repository });
  console.log(`${CHANGELOG_FILE} on ${values.branch}: ${result}`);
}
