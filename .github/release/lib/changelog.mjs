// Renders conventional commits as changelog entries grouped by section.

export const SECTIONS = ["Features", "Fixes", "Performance", "Refactoring", "Documentation", "Other"];

const TYPE_SECTION = {
  feat: "Features",
  fix: "Fixes",
  perf: "Performance",
  refactor: "Refactoring",
  docs: "Documentation",
};
const CONVENTIONAL = /^(\w+)(?:\(([^)]*)\))?!?:\s*(.*)$/;

function entry({ sha, subject }) {
  const m = CONVENTIONAL.exec(subject);
  if (!m) return { section: "Other", line: `- ${subject} (${sha.slice(0, 7)})` };
  const [, type, scope, text] = m;
  const prefix = scope ? `**${scope}:** ` : "";
  return { section: TYPE_SECTION[type] ?? "Other", line: `- ${prefix}${text} (${sha.slice(0, 7)})` };
}

/** Map of section -> lines, in section order, empty sections omitted. */
export function renderEntries(commits) {
  const grouped = new Map(SECTIONS.map((s) => [s, []]));
  for (const commit of commits) {
    const { section, line } = entry(commit);
    grouped.get(section).push(line);
  }
  return new Map([...grouped].filter(([, lines]) => lines.length > 0));
}
