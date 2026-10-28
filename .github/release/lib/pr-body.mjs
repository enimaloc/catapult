// Promotion PR body: a bot-maintained changelog the owner may edit by hand.

import { renderEntries, SECTIONS } from "./changelog.mjs";

const START = "<!-- changelog:start -->";
const END = "<!-- changelog:end -->";
const TOGGLE_TEXT = "<!-- changelog-bot --> Le bot met à jour le changelog";
const REMINDER =
  "Sors cette PR du draft pour publier la release ; elle fusionnera quand les checks seront verts.";
const TOGGLE = /^- \[( |x|X)\] <!-- changelog-bot -->/m;
const SEEN = /<!-- changelog:seen([^>]*?)-->/;
const HEADING = /^### (.+?)\s*$/;
const PROMOTED_HEAD = /<!-- promoted-head: ([0-9a-f]*) -->/;
const ENTRY = /^- /;
const OVERFLOW = /^… et (\d+) autres? commits?$/;

// GitHub refuses a PR body over 65,536 chars: the changelog shows at most MAX_ENTRIES commits, the
// others are counted on an overflow line, and the whole body is kept under MAX_BODY.
export const MAX_ENTRIES = 200;
export const MAX_BODY = 60000;

/** The form a commit takes in the seen marker: its 12-char short SHA (full SHAs read before map to it). */
export const seenId = (sha) => sha.slice(0, 12);
const seenMarker = (seen) => `<!-- changelog:seen ${seen.join(",")} -->`;

export function buildBody({ changelog, botEnabled, seen, promotedHead }) {
  const body = fit([
    REMINDER,
    "",
    `- [${botEnabled ? "x" : " "}] ${TOGGLE_TEXT}`,
    "",
    START,
    changelog,
    END,
    "",
    seenMarker(seen.map(seenId)),
    "",
  ].join("\n"));
  return promotedHead ? setPromotedHead(body, promotedHead) : body;
}

/** The source head SHA the bot last refreshed the PR for (hidden marker), or null. */
export function promotedHead(body) {
  return PROMOTED_HEAD.exec(body)?.[1] || null;
}

/** Writes the promoted-head marker, whatever the changelog toggle says. */
export function setPromotedHead(body, sha) {
  const marker = `<!-- promoted-head: ${sha} -->`;
  return PROMOTED_HEAD.test(body) ? body.replace(PROMOTED_HEAD, () => marker) : `${body.trimEnd()}\n${marker}\n`;
}

function region(body) {
  const start = body.indexOf(START);
  const end = body.indexOf(END, start + START.length);
  if (start < 0 || end < 0) return null;
  return { from: start + START.length, to: end };
}

export function parseBody(body) {
  const r = region(body);
  const seenMatch = SEEN.exec(body);
  const toggle = TOGGLE.exec(body);
  return {
    changelog: r ? body.slice(r.from, r.to).trim() : "",
    botEnabled: toggle ? toggle[1] !== " " : false,
    seen: seenMatch ? seenMatch[1].split(",").map((s) => seenId(s.trim())).filter(Boolean) : [],
  };
}

export function releaseNotes(body) {
  return parseBody(body).changelog;
}

// Inserts lines at the end of an existing section, or re-creates the heading at its ordered position.
function addToSection(lines, section, entries) {
  const headings = lines
    .map((line, i) => ({ i, name: HEADING.exec(line)?.[1] }))
    .filter((h) => h.name !== undefined);
  const own = headings.find((h) => h.name === section);
  if (own) {
    const next = headings.find((h) => h.i > own.i);
    let at = next ? next.i : lines.length;
    while (at > own.i + 1 && lines[at - 1].trim() === "") at--;
    lines.splice(at, 0, ...entries);
    return;
  }
  const rank = SECTIONS.indexOf(section);
  const after = headings.find((h) => SECTIONS.indexOf(h.name) > rank);
  if (after) {
    lines.splice(after.i, 0, `### ${section}`, ...entries, "");
    return;
  }
  while (lines.length > 0 && lines[lines.length - 1].trim() === "") lines.pop();
  if (lines.length > 0) lines.push("");
  lines.push(`### ${section}`, ...entries);
}

/** Removes the overflow line (and the blank lines before it) and returns the count it held. */
function takeOverflow(lines) {
  const at = lines.findIndex((line) => OVERFLOW.test(line.trim()));
  if (at < 0) return 0;
  const count = Number(OVERFLOW.exec(lines[at].trim())[1]);
  lines.splice(at, 1);
  while (lines.length > 0 && lines[lines.length - 1].trim() === "") lines.pop();
  return count;
}

function putOverflow(lines, count) {
  if (count === 0) return;
  while (lines.length > 0 && lines[lines.length - 1].trim() === "") lines.pop();
  if (lines.length > 0) lines.push("");
  lines.push(`… et ${count} ${count === 1 ? "autre commit" : "autres commits"}`);
}

// Drops the headings left without any entry, with the blank line that followed them.
function dropEmptySections(lines) {
  for (let i = lines.length - 1; i >= 0; i--) {
    if (!HEADING.test(lines[i])) continue;
    let j = i + 1;
    while (j < lines.length && !HEADING.test(lines[j]) && !ENTRY.test(lines[j]) && !OVERFLOW.test(lines[j].trim())) {
      j++;
    }
    if (j >= lines.length || !ENTRY.test(lines[j])) lines.splice(i, j - i);
  }
}

function withChangelog(body, lines) {
  const r = region(body);
  if (r) return `${body.slice(0, r.from)}\n${lines.join("\n")}\n${body.slice(r.to)}`;
  return `${body.trimEnd()}\n\n${START}\n${lines.join("\n")}\n${END}\n`;
}

/**
 * Hard guard on the body size: drops the last changelog entries into the overflow count, then, past
 * that, the oldest seen SHAs (those commits would come back as overflow on the next refresh).
 */
function fit(body) {
  if (body.length <= MAX_BODY) return body;
  const r = region(body);
  if (r) {
    const lines = body.slice(r.from, r.to).trim().split("\n");
    let overflow = takeOverflow(lines);
    let excess = body.length - MAX_BODY + 40; // room for the overflow line
    for (let i = lines.length - 1; i >= 0 && excess > 0; i--) {
      if (!ENTRY.test(lines[i])) continue;
      excess -= lines[i].length + 1;
      lines.splice(i, 1);
      overflow++;
    }
    dropEmptySections(lines);
    putOverflow(lines, overflow);
    body = withChangelog(body, lines);
  }
  const seen = SEEN.exec(body);
  if (body.length > MAX_BODY && seen) {
    const ids = seen[1].split(",").map((s) => s.trim()).filter(Boolean);
    const drop = Math.ceil((body.length - MAX_BODY) / 13);
    body = body.replace(SEEN, () => seenMarker(ids.slice(drop)));
  }
  return body;
}

export function updateBody(body, newCommits) {
  const { changelog, botEnabled, seen } = parseBody(body);
  if (!botEnabled) return body;
  const known = new Set(seen);
  const fresh = newCommits.filter((c) => !known.has(seenId(c.sha)));
  if (fresh.length === 0) return body;

  const lines = changelog === "" ? [] : changelog.split("\n");
  let overflow = takeOverflow(lines);
  const room = Math.max(0, MAX_ENTRIES - lines.filter((line) => ENTRY.test(line)).length);
  const shown = fresh.slice(0, room);
  overflow += fresh.length - shown.length;
  for (const [section, entries] of renderEntries(shown)) addToSection(lines, section, entries);
  putOverflow(lines, overflow);

  const out = withChangelog(body, lines);
  const marker = seenMarker([...seen, ...fresh.map((c) => seenId(c.sha))]);
  return fit(SEEN.test(out) ? out.replace(SEEN, () => marker) : `${out.trimEnd()}\n\n${marker}\n`);
}
