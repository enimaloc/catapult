// A promotion (or hotfix) PR was merged: tag its merge commit, hand the release notes to release.yml,
// and after a hotfix open the backport PRs main -> beta and beta -> dev.
// CLI: node .github/release/publish.mjs --number 12 --notes-file notes.md
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo; GITHUB_OUTPUT receives tag, prerelease and skip)

import { appendFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { parseVersion } from "./lib/version.mjs";
import { releaseNotes } from "./lib/pr-body.mjs";
import { createGitHub } from "./lib/github.mjs";
import { kindOf, titleVersion } from "./ready.mjs";

const short = (sha) => sha.slice(0, 7);

/** Annotated tag on the merge commit; an existing tag must already point there. */
async function ensureTag(gh, tag, sha, message) {
  const existing = await gh.getTag(tag);
  if (existing === null) {
    await gh.createTag({ tag, sha, message });
    console.log(`tagged ${short(sha)} as ${tag}`);
  } else if (existing !== sha) {
    throw new Error(`Tag ${tag} already points at ${short(existing)}, not at the merge commit ${short(sha)}`);
  }
}

function backportBody(tag, pr, from, to) {
  return (
    `Report du hotfix \`${tag}\` (#${pr.number}) de \`${from}\` vers \`${to}\`.\n\n` +
    `Fusionne cette PR par commit de merge pour ramener le correctif sur \`${to}\` ; tant qu'elle est ` +
    `ouverte, la promotion vers \`${to}\` est refusée.\n`
  );
}

/** Opens each backport PR whose pair has commits to bring and no open PR yet; returns their numbers. */
async function openBackports(gh, tag, pr) {
  const opened = [];
  for (const [from, to] of [
    ["main", "beta"],
    ["beta", "dev"],
  ]) {
    const [open, commits] = await Promise.all([gh.listPulls({ base: to, head: from }), gh.compareCommits(to, from)]);
    if (open.length || commits.length === 0) continue;
    const created = await gh.createPull({
      title: `chore(release): backport ${tag} to ${to}`,
      base: to,
      head: from,
      draft: false,
      labels: ["backport"],
      body: backportBody(tag, pr, from, to),
    });
    console.log(`opened the backport #${created.number} ${from} -> ${to}`);
    opened.push(created.number);
  }
  return opened;
}

async function release(gh, pr, kind) {
  if (!pr.merged || !pr.mergeCommitSha) throw new Error(`#${pr.number} is not merged`);
  const tag = titleVersion(pr.title, kind);
  if (tag === null) throw new Error(`#${pr.number}: the title does not carry the version to publish: ${pr.title}`);

  await ensureTag(gh, tag, pr.mergeCommitSha, pr.title);
  const skip = (await gh.getRelease(tag)) !== null;
  if (skip) console.log(`${tag} already has a GitHub Release: not published again`);
  const backports = kind === "hotfix" ? await openBackports(gh, tag, pr) : [];
  return { tag, prerelease: parseVersion(tag).beta !== null, notes: releaseNotes(pr.body ?? ""), skip, backports };
}

/**
 * -> {tag, prerelease, notes, skip, backports}: `skip` when the release already exists. Throws on an
 * unmerged or unrelated PR; a failure on a release PR is also reported on it.
 */
export async function publish({ gh, pr, owner, repo }) {
  const kind = kindOf(pr, `${owner}/${repo}`);
  if (kind === null) throw new Error(`#${pr.number} is not a promotion or hotfix pull request`);
  try {
    return await release(gh, pr, kind);
  } catch (error) {
    const version = titleVersion(pr.title, kind);
    if (pr.merged && version !== null) {
      try {
        await gh.comment(
          pr.number,
          `La publication de \`${version}\` a échoué après la fusion.\n\n\`\`\`\n${error.message}\n\`\`\`\n\n` +
            "Corrige le problème puis relance le workflow « Publish ».",
        );
      } catch (reportError) {
        console.error(`#${pr.number}: could not report the failure: ${reportError.message}`);
      }
    }
    throw error;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({ options: { number: { type: "string" }, "notes-file": { type: "string" } } });
  if (!values.number || !values["notes-file"]) throw new Error("Usage: publish.mjs --number <pr> --notes-file <path>");
  const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "").split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const pr = await gh.getPull(Number(values.number));
  const result = await publish({ gh, pr, owner, repo });
  writeFileSync(values["notes-file"], result.notes);
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(
      process.env.GITHUB_OUTPUT,
      `tag=${result.tag}\nprerelease=${result.prerelease}\nskip=${result.skip}\n`,
    );
  }
  console.log(JSON.stringify({ ...result, notes: undefined }));
}
