// The owner took a promotion (or hotfix) PR out of draft: bump the version in build.gradle.kts on
// the source branch, then merge (or enable auto-merge) pinned to that commit.
// CLI: node .github/release/ready.mjs --number 12 --head-sha <sha> --sender <login>
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo, APP_SLUG = the release App's slug)

import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { parseVersion } from "./lib/version.mjs";
import { promotedHead } from "./lib/pr-body.mjs";
import { createGitHub } from "./lib/github.mjs";
import { refreshDashboardSafely } from "./dashboard.mjs";
import {
  compareVersions,
  isReleaseCommit,
  promote as promoteImpl,
  releaseVersion,
  versionTags,
} from "./promote.mjs";

const GRADLE_FILE = "build.gradle.kts";
const VERSION_LINE = /^(\s*version\s*=\s*)"[^"]*"/m;
const TITLE = /^chore\(release\): (v\S+)$/;

/**
 * Replaces the first line-leading `version = "…"` assignment of the file (in build.gradle.kts, the
 * `allprojects` one); throws when there is none.
 */
export function bumpGradleVersion(text, version) {
  if (!VERSION_LINE.test(text)) throw new Error(`No version = "…" line in ${GRADLE_FILE}`);
  return text.replace(VERSION_LINE, (_, prefix) => `${prefix}"${version}"`);
}

/** Promotions dev -> beta and beta -> main, and hotfixes into main, all from this repository. */
export function kindOf(pull, repository) {
  if (pull.headRepo !== repository) return null;
  if (pull.head === "dev" && pull.base === "beta") return "beta";
  if (pull.head === "beta" && pull.base === "main") return "stable";
  if (pull.head.startsWith("hotfix/") && pull.base === "main") return "hotfix";
  return null;
}

/** The version held by the title, when it fits the kind of PR (beta into beta, stable into main). */
export function titleVersion(title, kind) {
  const match = TITLE.exec(title);
  if (!match) return null;
  let parsed;
  try {
    parsed = parseVersion(match[1]);
  } catch {
    return null;
  }
  return (parsed.beta !== null) === (kind === "beta") ? match[1] : null;
}

/** Next patch after the latest stable tag (v0.0.0 when there is none). */
function nextPatch(tags) {
  const stables = versionTags(tags).filter((v) => v.beta === null).sort(compareVersions);
  const last = stables.length ? stables[stables.length - 1] : { major: 0, minor: 0, patch: 0 };
  return `v${last.major}.${last.minor}.${last.patch + 1}`;
}

// The checks a release must pass before the App merges it itself: the App may bypass the ruleset,
// so a CLEAN merge state alone does not prove they ran. A promotion's commitlint job is skipped.
const REQUIRED_CHECKS = { build: ["success"], test: ["success"], commitlint: ["success", "skipped"] };

/** Whether every required check has run on the commit, each of its runs with an accepted conclusion. */
export function requiredChecksPassed(runs) {
  return Object.entries(REQUIRED_CHECKS).every(([name, accepted]) => {
    const own = runs.filter((r) => r.name === name);
    return own.length > 0 && own.every((r) => r.status === "completed" && accepted.includes(r.conclusion));
  });
}

const short = (sha) => sha.slice(0, 7);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
// GitHub moves a pull request's head a few seconds after the branch push.
const HEAD_READS = 10;
const HEAD_PAUSE_MS = 3000;
const AGAIN = "sors-la à nouveau du draft";

const MESSAGES = {
  title: (kind) =>
    `Le titre de la PR doit porter la version à publier ` +
    `(\`chore(release): vX.Y.Z${kind === "beta" ? "-BETA.N" : ""}\`) : la PR repasse en draft. ` +
    `Corrige le titre puis ${AGAIN}.`,
  notReady: () => "La PR n'est plus ouverte et prête : rien n'a été fusionné.",
  headMoved: () =>
    `La branche a bougé depuis le passage en ready : la PR repasse en draft. Relis les nouveaux commits puis ${AGAIN}.`,
  backport: (backports) =>
    `Un report de release est encore ouvert (` +
    backports.map((p) => `#${p.number} \`${p.head}\` → \`${p.base}\``).join(", ") +
    `) : fusionne d'abord le report. La PR repasse en draft ; ${AGAIN} ensuite.`,
  noMarker: () =>
    `Le bot n'a pas encore enregistré la tête de branche couverte par cette PR : la PR repasse en draft. ` +
    `Attends sa mise à jour puis ${AGAIN}.`,
  unseen: (commits) =>
    "Des commits sont arrivés après la dernière mise à jour du bot : la PR repasse en draft. " +
    `Attends la mise à jour du changelog, relis-le puis ${AGAIN}.\n\n` +
    commits.map((c) => `- ${short(c.sha)} ${c.subject}`).join("\n"),
  tagExists: (version, promotion) =>
    `Le tag \`${version}\` existe déjà : cette version est déjà publiée et la PR repasse en draft. ` +
    (promotion
      ? `Le bot corrige le titre à sa prochaine mise à jour (lancée maintenant) ; relis-le puis ${AGAIN}.`
      : `Corrige le titre (ou retire la version pour que le bot prenne le patch suivant) puis ${AGAIN}.`),
  staleVersion: (version, expected) =>
    `Le titre annonce \`${version}\` alors que les tags actuels donnent ` +
    (expected ? `\`${expected}\`` : "rien à publier") +
    ` : la PR repasse en draft. Le bot corrige le titre à sa prochaine mise à jour (lancée maintenant) ; ` +
    `relis-le puis ${AGAIN}.`,
  error: (error) =>
    `La préparation de la release a échoué : la PR repasse en draft.\n\n\`\`\`\n${error.message}\n\`\`\`\n\n` +
    `Corrige le problème puis ${AGAIN}.`,
};

/** Re-drafts the PR (when it is still an open, ready one) and explains why. */
async function refuse(gh, pull, message) {
  if (pull.state === "open" && !pull.draft) await gh.convertToDraft(pull);
  await gh.comment(pull.number, message);
  return "refused";
}

/** Re-drafts the promotion, then has the bot retitle it from the current tags (best effort). */
async function refuseAndRefresh(gh, pull, message, refresh) {
  const result = await refuse(gh, pull, message);
  try {
    await refresh();
  } catch (error) {
    console.warn(`::warning::#${pull.number}: could not refresh the promotion: ${error.message}`);
  }
  return result;
}

/** Waits until the pull request shows `sha` as its head: auto-merge pinned to it is refused before. */
async function waitForHead(gh, number, sha, wait) {
  let seen;
  for (let read = 1; read <= HEAD_READS; read++) {
    seen = (await gh.getPull(number)).headSha;
    if (seen === sha) return;
    if (read < HEAD_READS) await wait(HEAD_PAUSE_MS);
  }
  throw new Error(`#${number} still shows ${short(seen)} instead of the bump ${short(sha)}`);
}

async function prepare({ gh, pr, pull, kind, owner, repo, appSlug, promote, wait }) {
  if (pull.state !== "open" || pull.draft) return refuse(gh, pull, MESSAGES.notReady());
  if (pull.headSha !== pr.headSha) return refuse(gh, pull, MESSAGES.headMoved());

  let title = pull.title;
  let version = titleVersion(title, kind);
  if (version === null && kind === "hotfix") {
    version = nextPatch(await gh.listTags());
    title = `chore(release): ${version}`;
    await gh.updatePull(pull.number, { title });
  }
  if (version === null) return refuse(gh, pull, MESSAGES.title(kind));

  // The title was computed from the tags of the bot's last refresh: a release may have been tagged since.
  const promotion = kind !== "hotfix";
  const refresh = () => promote({ gh, source: pull.head, target: pull.base, owner, repo, appSlug });
  if ((await gh.getTag(version)) !== null) {
    const message = MESSAGES.tagExists(version, promotion);
    return promotion ? refuseAndRefresh(gh, pull, message, refresh) : refuse(gh, pull, message);
  }
  if (promotion) {
    const [tags, compared] = await Promise.all([gh.listTags(), gh.compareCommits(pull.base, pull.head)]);
    const promoted = compared.filter((c) => !isReleaseCommit(c, appSlug));
    const expected = releaseVersion(pull.base, tags, promoted);
    if (expected !== version) return refuseAndRefresh(gh, pull, MESSAGES.staleVersion(version, expected), refresh);
  }

  // A backport into either branch would otherwise conflict with (or be missing from) this promotion.
  const intoBranches = await Promise.all([gh.listPulls({ base: pull.base }), gh.listPulls({ base: pull.head })]);
  const backports = intoBranches.flat().filter((p) => p.labels?.includes("backport"));
  if (backports.length) return refuse(gh, pull, MESSAGES.backport(backports));

  // The owner validated the PR as the bot last refreshed it: nothing human may come after that head.
  // A hotfix PR is written by the owner, without a bot refresh.
  if (kind !== "hotfix") {
    const marker = promotedHead(pull.body ?? "");
    if (!marker) return refuse(gh, pull, MESSAGES.noMarker());
    if (marker !== pr.headSha) {
      const after = await gh.compareCommits(marker, pr.headSha);
      const unseen = after.filter((c) => !isReleaseCommit(c, appSlug));
      if (unseen.length) return refuse(gh, pull, MESSAGES.unseen(unseen));
    }
  }

  const current = await gh.getPull(pull.number);
  if (current.state !== "open" || current.draft) return refuse(gh, current, MESSAGES.notReady());
  if (current.headSha !== pr.headSha) return refuse(gh, current, MESSAGES.headMoved());

  const file = await gh.getFile(GRADLE_FILE, pr.headSha);
  const bumped = bumpGradleVersion(file.text, version.slice(1));
  if (bumped !== file.text) {
    const bump = await gh.commitFile({
      branch: pull.head,
      parent: pr.headSha,
      path: GRADLE_FILE,
      text: bumped,
      message: title,
    });
    if (bump === null) return refuse(gh, current, MESSAGES.headMoved());
    await waitForHead(gh, pull.number, bump, wait);
    await gh.enableAutoMerge(pull, { expectedHeadOid: bump, commitHeadline: title });
    console.log(`#${pull.number}: auto-merge on the bump ${short(bump)} for ${version}`);
    return "auto-merge";
  }

  // Auto-merge cannot be enabled on a PR that is already mergeable: merge it right away, once the
  // required checks are verified green on that very head (otherwise auto-merge waits for them).
  const pinned = { expectedHeadOid: pr.headSha, commitHeadline: title };
  if ((await gh.getMergeState(pull)) === "CLEAN" && requiredChecksPassed(await gh.listCheckRuns(pr.headSha))) {
    await gh.mergePull(pull, pinned);
    console.log(`#${pull.number}: merged ${short(pr.headSha)} for ${version}`);
    return "merged";
  }
  await gh.enableAutoMerge(pull, pinned);
  console.log(`#${pull.number}: auto-merge on ${short(pr.headSha)} for ${version}`);
  return "auto-merge";
}

/** -> "auto-merge" | "merged" | "ignored" | "refused"; any failure re-drafts the PR, then rethrows. */
export async function onReady({ gh, pr, sender, owner, repo, appSlug, promote = promoteImpl, wait = sleep }) {
  if (sender !== owner) return "ignored";
  const pull = await gh.getPull(pr.number);
  const kind = kindOf(pull, `${owner}/${repo}`);
  if (kind === null) return "ignored";

  try {
    return await prepare({ gh, pr, pull, kind, owner, repo, appSlug, promote, wait });
  } catch (error) {
    // Best effort: each report step on its own, so a failed re-draft still leaves the comment.
    for (const step of [() => gh.convertToDraft(pull), () => gh.comment(pull.number, MESSAGES.error(error))]) {
      try {
        await step();
      } catch (reportError) {
        console.error(`#${pull.number}: could not report the failure: ${reportError.message}`);
      }
    }
    throw error;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({
    options: { number: { type: "string" }, "head-sha": { type: "string" }, sender: { type: "string" } },
  });
  if (!values.number || !values["head-sha"] || !values.sender) {
    throw new Error("Usage: ready.mjs --number <pr> --head-sha <sha> --sender <login>");
  }
  const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "").split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const result = await onReady({
    gh,
    pr: { number: Number(values.number), headSha: values["head-sha"] },
    sender: values.sender,
    owner,
    repo,
    appSlug: process.env.APP_SLUG,
  });
  console.log(result);
  await refreshDashboardSafely({ gh });
}
