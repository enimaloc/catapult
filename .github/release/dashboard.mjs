// Keeps the pinned issue « Releases en cours »: GitHub cannot pin pull requests, so the bot pins an
// issue listing the open promotion PRs, the pending hotfix backports and the last releases.
// Called at the end of promote.mjs, ready.mjs and publish.mjs; it never makes a release step fail.

const MARKER = "<!-- release-dashboard -->";
const TITLE = "🚀 Releases en cours";
const LABEL = "release";
const PAIRS = new Set(["dev>beta", "beta>main"]);
const TITLE_VERSION = /^chore\(release\): (v\S+)$/;

// GitHub turns #N into a link to the pull request.
const link = (pull) => `#${pull.number}`;

/**
 * promotions: [{number, base, head, version, state}] with state draft | prête | prête (auto-merge) | en conflit;
 * backports: [{number, title, base, head}]; releases: [{tag, url, prerelease}].
 */
export function renderDashboard({ promotions, backports, releases }) {
  const lines = [MARKER, "", "Cette issue est tenue à jour par le bot : ne pas la modifier ni la fermer.", ""];

  lines.push("## Promotions en cours", "");
  if (promotions.length === 0) {
    lines.push("Aucune promotion en cours.");
  } else {
    lines.push("| Promotion | Version | État | PR |", "| --- | --- | --- | --- |");
    for (const p of promotions) {
      lines.push(`| \`${p.head}\` → \`${p.base}\` | ${p.version} | ${p.state} | ${link(p)} |`);
    }
  }

  lines.push("", "## Reports de hotfix en attente", "");
  if (backports.length === 0) {
    lines.push("Aucun report en attente.");
  } else {
    for (const b of backports) lines.push(`- ${link(b)} \`${b.head}\` → \`${b.base}\` : ${b.title}`);
  }

  lines.push("", "## Dernières releases", "");
  if (releases.length === 0) {
    lines.push("Aucune release publiée.");
  } else {
    for (const r of releases) lines.push(`- [${r.tag}](${r.url})${r.prerelease ? " (pré-release)" : ""}`);
  }
  return `${lines.join("\n")}\n`;
}

async function promotionState(gh, pull) {
  if (pull.draft) return "draft";
  if ((await gh.getMergeState(pull)) === "DIRTY") return "en conflit";
  return pull.autoMerge ? "prête (auto-merge)" : "prête";
}

async function collect(gh) {
  const open = await gh.listPulls({});
  const promotions = [];
  for (const pull of open.filter((p) => p.labels.includes("release") && PAIRS.has(`${p.head}>${p.base}`))) {
    promotions.push({
      number: pull.number,
      base: pull.base,
      head: pull.head,
      version: TITLE_VERSION.exec(pull.title)?.[1] ?? pull.title,
      state: await promotionState(gh, pull),
    });
  }
  const backports = open
    .filter((p) => p.labels.includes("backport"))
    .map((p) => ({ number: p.number, title: p.title, base: p.base, head: p.head }));
  return { promotions, backports, releases: await gh.listReleases(2) };
}

const normalize = (text) => text.replace(/\r\n/g, "\n").trim();

/**
 * Creates the issue when missing, keeps only the lowest-numbered one when concurrent runs made several,
 * pins it when it is not pinned, and rewrites its body only when it changed.
 */
export async function refreshDashboard({ gh }) {
  const body = renderDashboard(await collect(gh));
  let issues = await gh.findIssuesByLabel(LABEL, MARKER);
  let created = null;
  if (issues.length === 0) {
    created = await gh.createIssue({ title: TITLE, body, labels: [LABEL] });
    // Another workflow may have created one at the same time: look again.
    issues = await gh.findIssuesByLabel(LABEL, MARKER);
    if (issues.length === 0) issues = [{ ...created, pinned: false }];
  }
  const [kept, ...duplicates] = issues;
  for (const duplicate of duplicates) await gh.closeIssue(duplicate.number);

  // Its own try: a pin failure must not hide the outcome, and the next run pins again.
  if (!kept.pinned) {
    try {
      await gh.pinIssue(kept);
    } catch (error) {
      console.warn(`::warning::dashboard: could not pin issue #${kept.number}: ${error.message}`);
    }
  }

  if (created?.number === kept.number) return "created";
  if (normalize(kept.body) === normalize(body)) return "unchanged";
  await gh.updateIssue(kept.number, { body });
  return "updated";
}

/** Refreshes the dashboard; a failure is logged and swallowed so it never fails the release step. */
export async function refreshDashboardSafely({ gh, refresh = refreshDashboard }) {
  try {
    return await refresh({ gh });
  } catch (error) {
    console.warn(`::warning::dashboard: could not refresh the releases issue: ${error.message}`);
    return null;
  }
}
