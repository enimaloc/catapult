// CHANGELOG.md at the repository root: every published version, newest first, rendered from the
// release notes (the GitHub Releases, themselves taken from the hand-editable release PRs).

import { parseVersion } from "./version.mjs";
import { compareVersions } from "../promote.mjs";

export const CHANGELOG_FILE = "CHANGELOG.md";

const HEADER = [
  "# Changelog",
  "",
  "Toutes les versions publiées de Catapult, la plus récente en premier.",
  "",
  "Ce fichier est régénéré par le bot à chaque release à partir des notes des " +
    "[GitHub Releases](https://github.com/enimaloc/catapult/releases) : pour corriger une entrée, modifie " +
    "les notes de la release (ou le changelog de la PR de release avant sa publication), pas ce fichier.",
];

// A version section is `##`: the notes' own headings (### Features… or a generated « ## What's Changed »)
// are pushed under it.
function nestHeadings(notes) {
  return notes.replace(/^#{1,2}(?= )/gm, "###");
}

/**
 * releases: {tag, date (YYYY-MM-DD), body}[] -> the CHANGELOG.md text. Tags that are not versions
 * (vX.Y.Z or vX.Y.Z-BETA.N) are left out.
 */
export function renderChangelogFile(releases, repository = "enimaloc/catapult") {
  const versions = [];
  for (const release of releases) {
    try {
      versions.push({ ...release, version: parseVersion(release.tag) });
    } catch {
      // not one of our version tags
    }
  }
  versions.sort((a, b) => compareVersions(b.version, a.version));
  const sections = versions.map(({ tag, date, body }) => {
    const heading = `## [${tag}](https://github.com/${repository}/releases/tag/${tag})${date ? ` - ${date}` : ""}`;
    const notes = nestHeadings((body ?? "").trim()) || "_Aucune note._";
    return `${heading}\n\n${notes}`;
  });
  return `${[HEADER.join("\n"), ...sections].join("\n\n")}\n`;
}
