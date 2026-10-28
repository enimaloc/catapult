// The owner took a promotion (or hotfix) PR out of draft: bump the version in build.gradle.kts on
// the source branch, then enable auto-merge pinned to that commit.
// CLI: node .github/release/ready.mjs --number 12 --head-sha <sha> --sender <login>
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo, APP_SLUG = the release App's slug)

import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { parseVersion } from "./lib/version.mjs";
import { parseBody } from "./lib/pr-body.mjs";
import { createGitHub } from "./lib/github.mjs";
import { isReleaseCommit } from "./promote.mjs";

const GRADLE_FILE = "build.gradle.kts";
const VERSION_LINE = /^(\s*version\s*=\s*)"[^"]*"/m;
const TITLE = /^chore\(release\): (v\S+)$/;

/** Replaces the first `version = "…"` assignment (the `allprojects` one); throws when there is none. */
export function bumpGradleVersion(text, version) {
  if (!VERSION_LINE.test(text)) throw new Error(`No version = "…" line in ${GRADLE_FILE}`);
  return text.replace(VERSION_LINE, (_, prefix) => `${prefix}"${version}"`);
}

/** Promotions dev -> beta and beta -> main, and hotfixes into main. */
function kindOf(pull) {
  if (pull.head === "dev" && pull.base === "beta") return "beta";
  if (pull.head === "beta" && pull.base === "main") return "stable";
  if (pull.head.startsWith("hotfix/") && pull.base === "main") return "hotfix";
  return null;
}

/** The version held by the title, when it fits the kind of PR (beta into beta, stable into main). */
function titleVersion(pull, kind) {
  const match = TITLE.exec(pull.title);
  if (!match) return null;
  let parsed;
  try {
    parsed = parseVersion(match[1]);
  } catch {
    return null;
  }
  return (parsed.beta !== null) === (kind === "beta") ? match[1] : null;
}

const short = (sha) => sha.slice(0, 7);

function titleRefusal(kind) {
  const expected = kind === "beta" ? "chore(release): vX.Y.Z-BETA.N" : "chore(release): vX.Y.Z";
  return (
    `Le titre de la PR doit porter la version à publier (\`${expected}\`) : la PR repasse en draft. ` +
    `Corrige le titre puis sors-la à nouveau du draft.`
  );
}

function headMovedRefusal() {
  return (
    "La branche a bougé depuis le passage en ready : la PR repasse en draft. " +
    "Relis les nouveaux commits puis sors-la à nouveau du draft."
  );
}

function backportRefusal(base, backports) {
  const list = backports.map((p) => `#${p.number}`).join(", ");
  return (
    `Un report de hotfix vers \`${base}\` est encore ouvert (${list}) : fusionne d'abord le report. ` +
    `La PR repasse en draft ; sors-la à nouveau du draft ensuite.`
  );
}

function unseenRefusal(commits) {
  const list = commits.map((c) => `- ${short(c.sha)} ${c.subject}`).join("\n");
  return (
    "Des commits ne figurent pas encore dans le changelog de cette PR : la PR repasse en draft. " +
    `Attends la mise à jour du bot, relis le changelog puis sors-la à nouveau du draft.\n\n${list}`
  );
}

export async function onReady({ gh, pr, sender, owner, appSlug }) {
  if (sender !== owner) return "ignored";
  const pull = await gh.getPull(pr.number);
  const kind = kindOf(pull);
  if (kind === null) return "ignored";

  const refuse = async (message) => {
    await gh.convertToDraft(pull);
    await gh.comment(pull.number, message);
    return "refused";
  };

  const version = titleVersion(pull, kind);
  if (version === null) return refuse(titleRefusal(kind));
  if (pull.headSha !== pr.headSha) return refuse(headMovedRefusal());

  const backports = (await gh.listPulls({ base: pull.base })).filter((p) => p.labels?.includes("backport"));
  if (backports.length) return refuse(backportRefusal(pull.base, backports));

  // The owner validated the changelog as shown: every human commit must already be in it. A hotfix
  // PR is written by the owner, without a bot-maintained changelog.
  if (kind !== "hotfix") {
    const seen = new Set(parseBody(pull.body ?? "").seen);
    const commits = await gh.compareCommits(pull.base, pr.headSha);
    const unseen = commits.filter((c) => !isReleaseCommit(c, appSlug) && !seen.has(c.sha));
    if (unseen.length) return refuse(unseenRefusal(unseen));
  }

  const file = await gh.getFile(GRADLE_FILE, pr.headSha);
  const bumped = bumpGradleVersion(file.text, version.slice(1));
  let expectedHeadOid = pr.headSha;
  if (bumped !== file.text) {
    expectedHeadOid = await gh.updateFile({
      path: GRADLE_FILE,
      branch: pull.head,
      sha: file.sha,
      message: pull.title,
      text: bumped,
    });
  }
  await gh.enableAutoMerge(pull, { expectedHeadOid, commitHeadline: pull.title });
  console.log(`#${pull.number}: auto-merge on ${short(expectedHeadOid)} for ${version}`);
  return "auto-merge";
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
    appSlug: process.env.APP_SLUG,
  });
  console.log(result);
}
