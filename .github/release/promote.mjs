// Opens (in draft) or refreshes the single promotion PR from `source` to `target`.
// CLI: node .github/release/promote.mjs --source dev --target beta
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo, APP_SLUG = the release App's slug)

import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { parseVersion, bumpKind, nextBeta, nextStable } from "./lib/version.mjs";
import { buildBody, parseBody, seenId, setPromotedHead, updateBody } from "./lib/pr-body.mjs";
import { createGitHub } from "./lib/github.mjs";
import { refreshDashboardSafely } from "./dashboard.mjs";

const RELEASE_SUBJECT = /^chore\(release\): v/;
const BACKPORT_SUBJECT = /^chore\(release\): backport v/;

export function versionTags(tags) {
  const versions = [];
  for (const tag of tags) {
    try {
      versions.push({ tag, ...parseVersion(tag) });
    } catch {
      // not one of our version tags
    }
  }
  return versions;
}

// Orders by X.Y.Z, then a beta before its stable, then by beta number.
export function compareVersions(a, b) {
  return (
    a.major - b.major ||
    a.minor - b.minor ||
    a.patch - b.patch ||
    (a.beta ?? Infinity) - (b.beta ?? Infinity)
  );
}

/**
 * Stable for a promotion to `main`, beta otherwise; null when there is nothing to release. `commits`
 * are the promoted ones (release commits left out). ready.mjs checks the PR title against it.
 */
export function releaseVersion(target, tags, commits) {
  const versions = versionTags(tags).sort(compareVersions);
  const stables = versions.filter((v) => v.beta === null);
  const lastStable = stables.length ? stables[stables.length - 1] : { tag: "v0.0.0", ...parseVersion("v0.0.0") };
  const betas = versions.filter((v) => v.beta !== null && compareVersions(v, lastStable) > 0);
  if (target === "main") return betas.length ? nextStable(betas[betas.length - 1].tag) : null;
  const messages = commits.map((c) => c.message);
  if (bumpKind(messages, lastStable.tag) === null && betas.length === 0) return null;
  return nextBeta(lastStable.tag, betas.map((v) => v.tag), messages);
}

/**
 * The bot's own commits: the version bump pushed on the source branch, release merge commits
 * (a promotion merged by auto-merge is credited to the App) and the merges of backport PRs. They are
 * never promoted on their own.
 */
export function isReleaseCommit(commit, appSlug) {
  if (BACKPORT_SUBJECT.test(commit.subject)) return true;
  if (!RELEASE_SUBJECT.test(commit.subject)) return false;
  if (commit.parents.length > 1) return true;
  return Boolean(appSlug) && commit.author === `${appSlug}[bot]`;
}

function backToDraftComment(source, version) {
  return (
    `De nouveaux commits sont arrivés sur \`${source}\` : la PR repasse en draft et l'auto-merge est ` +
    `désactivé, la validation précédente ne couvrait pas ces commits. Relis le changelog puis sors-la ` +
    `à nouveau du draft pour publier \`${version}\`.`
  );
}

export async function promote({ gh, source, target, owner, repo, appSlug }) {
  const [pulls, compared] = await Promise.all([
    gh.listPulls({ base: target, head: source }),
    gh.compareCommits(target, source),
  ]);
  const commits = compared.filter((c) => !isReleaseCommit(c, appSlug));
  // The source head this refresh covers: ready.mjs refuses to merge anything human pushed after it.
  const head = compared.length ? compared[compared.length - 1].sha : null;
  const pull = pulls[0];
  const withNumber = (action) => (pull ? { action, number: pull.number } : { action });

  if (commits.length === 0) {
    if (!pull) return { action: "none" };
    await gh.updatePull(pull.number, { state: "closed" });
    return { action: "closed", number: pull.number };
  }

  const version = releaseVersion(target, await gh.listTags(), commits);
  if (version === null) return withNumber("none");
  const title = `chore(release): ${version}`;

  if (!pull) {
    const created = await gh.createPull({
      title,
      base: target,
      head: source,
      draft: true,
      labels: ["release"],
      // Rendered like a refresh of an empty changelog: capped, every commit recorded as seen.
      body: updateBody(buildBody({ changelog: "", botEnabled: true, seen: [], promotedHead: head }), commits),
    });
    console.log(`${owner}/${repo}: opened #${created.number} ${title}`);
    return { action: "created", number: created.number };
  }

  const seen = new Set(parseBody(pull.body ?? "").seen);
  const fresh = commits.filter((c) => !seen.has(seenId(c.sha)));
  if (fresh.length === 0 && pull.title === title) return withNumber("none");

  await gh.updatePull(pull.number, { title, body: setPromotedHead(updateBody(pull.body ?? "", fresh), head) });
  if (!pull.draft) {
    // The owner's approval covered the previous head only (auto-merge is pinned to it).
    if (pull.autoMerge) await gh.disableAutoMerge(pull);
    await gh.convertToDraft(pull);
    await gh.comment(pull.number, backToDraftComment(source, version));
  }
  console.log(`${owner}/${repo}: refreshed #${pull.number} ${title}`);
  return { action: "updated", number: pull.number };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({ options: { source: { type: "string" }, target: { type: "string" } } });
  if (!values.source || !values.target) throw new Error("Usage: promote.mjs --source <branch> --target <branch>");
  const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "").split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const result = await promote({ ...values, gh, owner, repo, appSlug: process.env.APP_SLUG });
  console.log(JSON.stringify(result));
  await refreshDashboardSafely({ gh });
}
