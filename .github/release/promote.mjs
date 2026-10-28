// Opens (in draft) or refreshes the single promotion PR from `source` to `target`.
// CLI: node .github/release/promote.mjs --source dev --target beta
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo, APP_SLUG = the release App's slug)

import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { parseVersion, nextBeta, nextStable } from "./lib/version.mjs";
import { renderEntries } from "./lib/changelog.mjs";
import { buildBody, parseBody, updateBody } from "./lib/pr-body.mjs";
import { createGitHub } from "./lib/github.mjs";

const RELEASE_SUBJECT = /^chore\(release\): v/;

function versionTags(tags) {
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
function compareVersions(a, b) {
  return (
    a.major - b.major ||
    a.minor - b.minor ||
    a.patch - b.patch ||
    (a.beta ?? Infinity) - (b.beta ?? Infinity)
  );
}

/** Stable for a promotion to `main`, beta otherwise; null when there is nothing to release. */
function releaseVersion(target, tags, commits) {
  const versions = versionTags(tags).sort(compareVersions);
  const stables = versions.filter((v) => v.beta === null);
  const lastStable = stables.length ? stables[stables.length - 1] : { tag: "v0.0.0", ...parseVersion("v0.0.0") };
  const betas = versions.filter((v) => v.beta !== null && compareVersions(v, lastStable) > 0);
  if (target === "main") return betas.length ? nextStable(betas[betas.length - 1].tag) : null;
  try {
    return nextBeta(lastStable.tag, betas.map((v) => v.tag), commits.map((c) => c.message));
  } catch {
    return null; // no releasable commit and no beta to continue
  }
}

function isBotBump(commit, appSlug) {
  return Boolean(appSlug) && commit.author === `${appSlug}[bot]` && RELEASE_SUBJECT.test(commit.subject);
}

function changelogOf(commits) {
  return [...renderEntries(commits)].map(([section, lines]) => [`### ${section}`, ...lines].join("\n")).join("\n\n");
}

function backToDraftComment(source, version) {
  return (
    `De nouveaux commits sont arrivés sur \`${source}\` : la PR repasse en draft et l'auto-merge est ` +
    `désactivé, la validation précédente ne couvrait pas ces commits. Relis le changelog puis sors-la ` +
    `à nouveau du draft pour publier \`${version}\`.`
  );
}

export async function promote({ gh, source, target, owner, repo, appSlug }) {
  const [pulls, commits] = await Promise.all([
    gh.listPulls({ base: target, head: source }),
    gh.compareCommits(target, source),
  ]);
  const pull = pulls[0];
  const withNumber = (action) => (pull ? { action, number: pull.number } : { action });

  if (commits.length === 0) {
    if (!pull) return { action: "none" };
    await gh.updatePull(pull.number, { state: "closed" });
    return { action: "closed", number: pull.number };
  }
  if (isBotBump(commits[commits.length - 1], appSlug)) return withNumber("none");

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
      body: buildBody({ changelog: changelogOf(commits), botEnabled: true, seen: commits.map((c) => c.sha) }),
    });
    console.log(`${owner}/${repo}: opened #${created.number} ${title}`);
    return { action: "created", number: created.number };
  }

  const seen = new Set(parseBody(pull.body ?? "").seen);
  const fresh = commits.filter((c) => !seen.has(c.sha));
  if (fresh.length === 0 && pull.title === title) return withNumber("none");

  await gh.updatePull(pull.number, { title, body: updateBody(pull.body ?? "", fresh) });
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
}
