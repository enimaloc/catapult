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

export function buildBody({ changelog, botEnabled, seen, promotedHead }) {
  const body = [
    REMINDER,
    "",
    `- [${botEnabled ? "x" : " "}] ${TOGGLE_TEXT}`,
    "",
    START,
    changelog,
    END,
    "",
    `<!-- changelog:seen ${seen.join(",")} -->`,
    "",
  ].join("\n");
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
    seen: seenMatch ? seenMatch[1].split(",").map((s) => s.trim()).filter(Boolean) : [],
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

export function updateBody(body, newCommits) {
  const { changelog, botEnabled, seen } = parseBody(body);
  if (!botEnabled) return body;
  const known = new Set(seen);
  const fresh = newCommits.filter((c) => !known.has(c.sha));
  if (fresh.length === 0) return body;

  const lines = changelog === "" ? [] : changelog.split("\n");
  for (const [section, entries] of renderEntries(fresh)) addToSection(lines, section, entries);

  let out = body;
  const r = region(out);
  if (r) out = `${out.slice(0, r.from)}\n${lines.join("\n")}\n${out.slice(r.to)}`;
  else out = `${out.trimEnd()}\n\n${START}\n${lines.join("\n")}\n${END}\n`;

  const marker = `<!-- changelog:seen ${[...seen, ...fresh.map((c) => c.sha)].join(",")} -->`;
  return SEEN.test(out) ? out.replace(SEEN, () => marker) : `${out.trimEnd()}\n\n${marker}\n`;
}
