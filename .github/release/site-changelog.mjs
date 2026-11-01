// The changelog built into catapult-api's image for the site's page: every published release's notes plus
// those of the version being released, whose GitHub Release is only created once its images are pushed.
// Run by release.yml; catapult-api falls back on its CHANGELOG.md when this file is missing.
// CLI: node .github/release/site-changelog.mjs --tag v1.0.0 --out changelog.md
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo, NOTES=the version's notes)

import { writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { renderChangelogFile } from "./lib/changelog-file.mjs";
import { createGitHub } from "./lib/github.mjs";

/** The published releases and the version being released -> the CHANGELOG.md text. */
export function siteChangelog({ releases, tag, notes, repository }) {
  const published = releases
    .filter((r) => r.tag !== tag)
    .map((r) => ({ tag: r.tag, body: r.body, date: r.publishedAt?.slice(0, 10) ?? null }));
  return renderChangelogFile([...published, { tag, body: notes, date: new Date().toISOString().slice(0, 10) }], repository);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({ options: { tag: { type: "string" }, out: { type: "string" } } });
  if (!values.tag || !values.out) throw new Error("Usage: site-changelog.mjs --tag <tag> --out <path>");
  const repository = process.env.GITHUB_REPOSITORY ?? "";
  const [owner, repo] = repository.split("/");
  const gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
  const releases = await gh.listAllReleases();
  writeFileSync(values.out, siteChangelog({ releases, tag: values.tag, notes: process.env.NOTES ?? "", repository }));
  console.log(`${values.out}: ${releases.length} published releases and ${values.tag}`);
}
