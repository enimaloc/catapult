// Scheduled releases. --confirm answers the owner's edits of a promotion PR: only the App's comment
// makes a date active. --due lists the source branches whose date has come, and --run takes their PR
// out of draft, then follows the same path as the owner doing it.
// CLI: node .github/release/schedule.mjs --confirm | --due | --run --source dev --target beta
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo, APP_SLUG = the release App's slug; GITHUB_EVENT_PATH
// for --confirm, GITHUB_OUTPUT for --due)

import { appendFileSync, readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { scheduledText, setScheduledText } from "./lib/pr-body.mjs";
import { activeSchedule, formatParis, NO_SCHEDULE, parseScheduleDate, scheduleMarker } from "./lib/schedule.mjs";
import { createGitHub } from "./lib/github.mjs";
import { isReleaseCommit } from "./promote.mjs";
import { kindOf, onReady } from "./ready.mjs";

export const PROMOTIONS = [
  { source: "dev", target: "beta" },
  { source: "beta", target: "main" },
];
// Past this delay, the start of the release says it is late (GitHub's schedules may be delayed).
export const LATE_NOTICE_MS = 60 * 60 * 1000;

const FORMAT = "`AAAA-MM-JJ HH:MM`, en heure de Paris";
const short = (sha) => sha.slice(0, 7);

/** Clears the PR's date and records in a comment that no schedule is active any more. */
async function clearSchedule(gh, pull, message) {
  await gh.updatePull(pull.number, { body: setScheduledText(pull.body ?? "", "") });
  await gh.comment(pull.number, `${message}\n\n${NO_SCHEDULE}`);
}

/** -> "ignored" | "confirmed" | "refused" | "cancelled". Event: {action, number, sender, previousBody}. */
export async function confirm({ gh, event, owner, repo, appSlug, now = new Date() }) {
  if (event.sender !== owner) return "ignored";
  const pull = await gh.getPull(event.number);
  const kind = kindOf(pull, `${owner}/${repo}`);
  if ((kind !== "beta" && kind !== "stable") || pull.state !== "open") return "ignored";

  if (event.action !== "edited") {
    if (activeSchedule(await gh.listComments(pull.number), appSlug) === null) return "ignored";
    await clearSchedule(gh, pull, "La PR a changé d'état à la main : la sortie programmée est annulée.");
    return "cancelled";
  }
  if (event.previousBody === undefined) return "ignored";
  const text = scheduledText(pull.body);
  if (text === scheduledText(event.previousBody)) return "ignored";

  const active = activeSchedule(await gh.listComments(pull.number), appSlug);
  if (text === "") {
    if (active === null) return "ignored";
    await gh.comment(pull.number, `La sortie programmée est annulée.\n\n${NO_SCHEDULE}`);
    return "cancelled";
  }
  const at = parseScheduleDate(text);
  if (at === null || at <= now || !pull.draft) {
    const reason =
      at === null
        ? `La date \`${text}\` est illisible ou n'existe pas : le format attendu est ${FORMAT}.`
        : at <= now
          ? `La date \`${text}\` est déjà passée.`
          : "La PR n'est plus en draft.";
    await gh.comment(pull.number, `${reason} Rien n'est programmé.\n\n${NO_SCHEDULE}`);
    return "refused";
  }
  await gh.comment(
    pull.number,
    `Sortie programmée le ${formatParis(at)} (heure de Paris), sur \`${short(pull.headSha)}\`. ` +
      `Tout nouveau commit l'annule.\n\n${scheduleMarker(at, pull.headSha)}`,
  );
  return "confirmed";
}

/** The source branches of the open promotions whose scheduled date has come. */
export async function dueSources({ gh, appSlug, now = new Date() }) {
  const due = [];
  for (const { source, target } of PROMOTIONS) {
    const [pull] = await gh.listPulls({ base: target, head: source });
    if (!pull) continue;
    const active = activeSchedule(await gh.listComments(pull.number), appSlug);
    if (active !== null && active.at <= now) due.push(source);
  }
  return due;
}

/** -> "none" | "waiting" | "already-ready" | "cancelled" | what onReady returned. */
export async function run({ gh, source, target, owner, repo, appSlug, now = new Date(), ready = onReady }) {
  const [pull] = await gh.listPulls({ base: target, head: source });
  if (!pull) return "none";
  const active = activeSchedule(await gh.listComments(pull.number), appSlug);
  if (active === null) return "none";
  if (now < active.at) return "waiting";

  if (!pull.draft) {
    await clearSchedule(gh, pull, "La PR est déjà sortie du draft : la sortie programmée est retirée.");
    return "already-ready";
  }
  if (pull.headSha !== active.head) {
    const after = await gh.compareCommits(active.head, pull.headSha);
    const unseen = after.filter((c) => !isReleaseCommit(c, appSlug));
    if (unseen.length) {
      await clearSchedule(
        gh,
        pull,
        `De nouveaux commits sont arrivés sur \`${source}\` : la sortie programmée est annulée. ` +
          `Relis-les puis indique une nouvelle date pour la reprogrammer.\n\n` +
          unseen.map((c) => `- ${short(c.sha)} ${c.subject}`).join("\n"),
      );
      return "cancelled";
    }
  }

  // The schedule is spent before anything happens: a later run never starts the release twice.
  const late = now - active.at > LATE_NOTICE_MS;
  await clearSchedule(
    gh,
    pull,
    late
      ? `La sortie programmée démarre en retard : prévue le ${formatParis(active.at)}, ` +
          `lancée le ${formatParis(now)} (heure de Paris).`
      : "La sortie programmée démarre.",
  );
  try {
    await gh.markReady(pull);
  } catch (error) {
    await gh.comment(pull.number, `La PR n'a pas pu sortir du draft : rien n'est publié.\n\n\`\`\`\n${error.message}\n\`\`\``);
    throw error;
  }
  // The App's own ready_for_review is ignored by release-ready.yml: the release starts here.
  return ready({ gh, pr: { number: pull.number, headSha: pull.headSha }, sender: owner, owner, repo, appSlug });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({
    options: {
      confirm: { type: "boolean" },
      due: { type: "boolean" },
      run: { type: "boolean" },
      source: { type: "string" },
      target: { type: "string" },
    },
  });
  const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "").split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const appSlug = process.env.APP_SLUG;
  if (values.confirm) {
    const payload = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, "utf8"));
    const event = {
      action: payload.action,
      number: payload.pull_request.number,
      sender: payload.sender.login,
      previousBody: payload.changes?.body?.from,
    };
    console.log(await confirm({ gh, event, owner, repo, appSlug }));
  } else if (values.due) {
    const due = await dueSources({ gh, appSlug });
    console.log(due);
    appendFileSync(process.env.GITHUB_OUTPUT, `sources=${JSON.stringify(due)}\n`);
  } else if (values.run && values.source && values.target) {
    console.log(await run({ gh, source: values.source, target: values.target, owner, repo, appSlug }));
  } else {
    throw new Error("Usage: schedule.mjs --confirm | --due | --run --source <branch> --target <branch>");
  }
}
