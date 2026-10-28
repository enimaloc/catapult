// A promotion (or hotfix) PR was merged: tag its merge commit, and after a hotfix open the backport
// PRs main -> beta and beta -> dev. The tag is pushed with the App token, so its push event runs
// release.yml, the single publication path (it reads the PR's notes itself).
// CLI: node .github/release/publish.mjs --number 12
// (env: GH_TOKEN = the App token, GITHUB_REPOSITORY=owner/repo, APP_SLUG = the release App's slug)

import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { createGitHub } from "./lib/github.mjs";
import { refreshDashboardSafely } from "./dashboard.mjs";
import { kindOf, titleVersion } from "./ready.mjs";
import { promote as promoteImpl } from "./promote.mjs";

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

async function release(gh, pr, kind, refreshStable) {
  if (!pr.merged || !pr.mergeCommitSha) throw new Error(`#${pr.number} is not merged`);
  const tag = titleVersion(pr.title, kind);
  if (tag === null) throw new Error(`#${pr.number}: the title does not carry the version to publish: ${pr.title}`);

  await ensureTag(gh, tag, pr.mergeCommitSha, pr.title);
  if (kind === "beta") await refreshStable();
  const backports = kind === "hotfix" ? await openBackports(gh, tag, pr) : [];
  return { tag, backports };
}

/**
 * -> {tag, backports} (numbers of the backport PRs opened). Throws on an unmerged or unrelated PR; a
 * failure on a release PR is also reported on it.
 */
export async function publish({ gh, pr, owner, repo, appSlug, promote = promoteImpl }) {
  const kind = kindOf(pr, `${owner}/${repo}`);
  if (kind === null) throw new Error(`#${pr.number} is not a promotion or hotfix pull request`);
  // The beta -> main PR was titled before this beta's tag existed: retitle it now (best effort).
  const refreshStable = async () => {
    try {
      await promote({ gh, source: "beta", target: "main", owner, repo, appSlug });
    } catch (error) {
      console.warn(`::warning::could not refresh the beta -> main promotion: ${error.message}`);
    }
  };
  try {
    return await release(gh, pr, kind, refreshStable);
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
  const { values } = parseArgs({ options: { number: { type: "string" } } });
  if (!values.number) throw new Error("Usage: publish.mjs --number <pr>");
  const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "").split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const pr = await gh.getPull(Number(values.number));
  console.log(JSON.stringify(await publish({ gh, pr, owner, repo, appSlug: process.env.APP_SLUG })));
  await refreshDashboardSafely({ gh });
}
