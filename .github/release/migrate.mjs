// One-off retroactive renumbering of the past releases: v0.0.0 on the initial commit, then the 96 legacy
// tags (v0.0.1-ALPHA … v0.3.7-BETA, in tag creation order) as v1.0.0-BETA.1 … v1.0.0-BETA.96, each with
// its GitHub Release and its GHCR image tags. Phases, each run only once the previous one is checked:
//   plan     prints the plan (local git only, no network); each tag's changelog base is the latest earlier
//            tag whose commit is an ancestor of it (git merge-base --is-ancestor), else the initial commit
//   create   disables every active workflow (pushing a tag runs the workflows of the tagged commit, whose
//            old release.yml rebuilds the images and moves :latest), then creates the annotated tags, the
//            releases and the image tags (:beta -> the last one; :latest is never touched)
//   verify   checks every new object and writes a report; when everything is right, records it and
//            re-enables the workflows
//   restore  re-enables exactly the workflows this script disabled (after an interrupted run)
//   delete   only after a passing verify of the very same plan, workflows disabled again as for create
//            and re-enabled once everything is deleted: the old releases, tags and v/<old> branches; old
//            image tags are kept (GHCR cannot drop a tag without deleting its digest)
//   changelog  writes CHANGELOG.md at the repository root (local git only, no network) with the very
//            notes create gives the releases; changelog.mjs regenerates it from the releases afterwards
// Dry-run by default; --execute performs (and logs) the writes. Every phase skips what already exists.
// CLI: node .github/release/migrate.mjs --phase plan|create|verify|restore|delete|changelog [--execute]
// (env: GH_TOKEN, GITHUB_REPOSITORY=owner/repo; `docker login ghcr.io` for the image steps; all the
// legacy tags fetched locally)

import { createHash } from "node:crypto";
import { execFile, execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { parseArgs, promisify } from "node:util";
import { fileURLToPath, pathToFileURL } from "node:url";
import { renderEntries } from "./lib/changelog.mjs";
import { CHANGELOG_FILE, renderChangelogFile } from "./lib/changelog-file.mjs";
import { createGitHub } from "./lib/github.mjs";

export const INIT_COMMIT = "cc49904209347d5522b395c276a6148f79deff33";
export const LEGACY_TAG_COUNT = 96;
export const IMAGES = ["ghcr.io/enimaloc/catapult/api", "ghcr.io/enimaloc/catapult/web"];
export const PHASES = ["plan", "create", "verify", "restore", "delete", "changelog"];

const LEGACY_TAG = /^v\d+\.\d+\.\d+-(ALPHA|BETA)$/;
const BETA_CHANNEL = "beta";
const CHANGELOG_LIMIT = 200;
const STATE_FILE = fileURLToPath(new URL(".migration-state.json", import.meta.url));
const reportFile = (phase) => fileURLToPath(new URL(`.migration-${phase}.md`, import.meta.url));

const short = (sha) => sha.slice(0, 7);
const imageTag = (version) => version.slice(1);

/** Legacy tags created in the same second (v0.1.7-BETA, v0.1.8-BETA) keep their version order. */
function byVersion(a, b) {
  const [, x, xLabel] = /^v([\d.]+)-(\w+)$/.exec(a);
  const [, y, yLabel] = /^v([\d.]+)-(\w+)$/.exec(b);
  const xs = x.split(".").map(Number);
  const ys = y.split(".").map(Number);
  return xs[0] - ys[0] || xs[1] - ys[1] || xs[2] - ys[2] || xLabel.localeCompare(yLabel);
}

/**
 * tags: {name, sha (commit), created (ISO date)}[] -> {name (old tag, null for v0.0.0), newName, sha, base,
 * nonLinear}[]: v0.0.0 on `initSha`, then the legacy tags by creation date as v1.0.0-BETA.1… (other tags
 * ignored). `base` (the newName the changelog starts from) is the latest earlier entry whose commit is an
 * ancestor, per `isAncestor(ancestorSha, sha)`; when none is, v0.0.0 with `nonLinear` set.
 */
export function planMigration(tags, initSha, { isAncestor }) {
  const legacy = tags
    .filter((t) => LEGACY_TAG.test(t.name))
    .sort((a, b) => Date.parse(a.created) - Date.parse(b.created) || byVersion(a.name, b.name));
  const plan = [{ name: null, newName: "v0.0.0", sha: initSha, base: null, nonLinear: false }];
  for (const [i, t] of legacy.entries()) {
    const base = plan
      .slice(1)
      .reverse()
      .find((earlier) => isAncestor(earlier.sha, t.sha));
    plan.push({
      name: t.name,
      newName: `v1.0.0-BETA.${i + 1}`,
      sha: t.sha,
      base: base?.newName ?? "v0.0.0",
      nonLinear: base === undefined && i > 0,
    });
  }
  return plan;
}

/** Identifies a plan: delete only trusts a verify recorded for the same hash. */
export function planHash(plan) {
  return createHash("sha256").update(JSON.stringify(plan)).digest("hex");
}

/** GHCR through `docker buildx imagetools`, run with `exec(file, args) -> {stdout}` (rejects on failure). */
export function createRegistry({ exec }) {
  return {
    /** The manifest digest of `ref`, or null when the image tag does not exist. */
    async digest(ref) {
      try {
        const args = ["buildx", "imagetools", "inspect", ref, "--format", "{{json .Manifest}}"];
        const { stdout } = await exec("docker", args);
        return JSON.parse(stdout).digest;
      } catch (error) {
        const detail = `${error.stderr ?? ""} ${error.message}`;
        if (/not found|manifest unknown/i.test(detail)) return null;
        throw new Error(`docker buildx imagetools inspect ${ref}: ${detail.trim()}`);
      }
    },

    /** Points `target` at the manifest of `source` (same digest, nothing rebuilt). */
    async tag(source, target) {
      await exec("docker", ["buildx", "imagetools", "create", "-t", target, source]);
    },
  };
}

/** Runs `action` with --execute, else only says what it would do; logs either way. */
async function write(ctx, description, action) {
  if (ctx.dryRun) {
    ctx.log(`[simulation] ${description}`);
    return;
  }
  await action();
  ctx.log(`[fait] ${description}`);
}

function updateState(ctx, change) {
  const state = ctx.store.read();
  change(state);
  ctx.store.write(state);
}

// --- create ---

/** Disables every active workflow, recording each one; throws (`refused` first) if any could not be. */
async function disableWorkflows(ctx, refused) {
  const failures = [];
  for (const workflow of await ctx.gh.listWorkflows()) {
    if (workflow.state !== "active") continue;
    try {
      await write(ctx, `désactivation du workflow ${workflow.name} (${workflow.id})`, async () => {
        await ctx.gh.disableWorkflow(workflow.id);
        try {
          updateState(ctx, (state) => {
            state.disabledWorkflows = [...new Set([...(state.disabledWorkflows ?? []), workflow.id])];
          });
        } catch (error) {
          throw new Error(
            `désactivé mais non enregistré dans ${STATE_FILE} (${error.message}) : réactive-le à la main ` +
              `après la migration (gh workflow enable ${workflow.id})`,
          );
        }
      });
    } catch (error) {
      failures.push(`${workflow.name} (${workflow.id}) : ${error.message}`);
    }
  }
  if (failures.length) {
    throw new Error(
      `${refused}, des workflows n'ont pas pu être désactivés : ${failures.join(" ; ")}. ` +
        "Relance la phase, ou réactive ceux déjà désactivés avec --phase restore.",
    );
  }
}

function tagMessage(entry) {
  return entry.name
    ? `Release ${entry.newName} (formerly ${entry.name})`
    : `Release ${entry.newName} (initial commit)`;
}

function changelogOf(commits) {
  return [...renderEntries(commits)].map(([section, lines]) => [`### ${section}`, ...lines].join("\n")).join("\n\n");
}

async function releaseBody(ctx, entry, plan) {
  if (!entry.name) return "Commit initial du projet, point de départ de la numérotation des versions.\n";
  const base = plan.find((e) => e.newName === entry.base);
  const commits = await ctx.gh.compareCommits(base.sha, entry.sha);
  const parts = [changelogOf(commits.slice(0, CHANGELOG_LIMIT)) || "_Aucun changement._"];
  if (commits.length > CHANGELOG_LIMIT) parts.push(`… et ${commits.length - CHANGELOG_LIMIT} autres commits.`);
  if (entry.nonLinear) parts.push("Historique non linéaire : comparé au commit initial.");
  parts.push(`Anciennement \`${entry.name}\`.`);
  return `${parts.join("\n\n")}\n`;
}

/** -> whether the tag is (or would be) created; an existing tag must already be on the planned commit. */
async function ensureTag(ctx, entry) {
  const existing = await ctx.gh.getTag(entry.newName);
  if (existing === entry.sha) return false;
  if (existing !== null) {
    throw new Error(`${entry.newName} existe déjà sur ${short(existing)}, pas sur ${short(entry.sha)}`);
  }
  await write(ctx, `tag ${entry.newName} sur ${short(entry.sha)} (${tagMessage(entry)})`, () =>
    ctx.gh.createTag({ tag: entry.newName, sha: entry.sha, message: tagMessage(entry) }),
  );
  return true;
}

async function ensureRelease(ctx, entry, plan) {
  if ((await ctx.gh.getReleaseByTag(entry.newName)) !== null) return false;
  const body = await releaseBody(ctx, entry, plan);
  const prerelease = entry.name !== null;
  await write(ctx, `release ${entry.newName}${prerelease ? " (pré-release)" : ""}`, () =>
    ctx.gh.createRelease({ tag: entry.newName, name: entry.newName, body, prerelease, makeLatest: !prerelease }),
  );
  return true;
}

/** -> "tagged" (or would be), "skipped" (already there) or "noImage" (the source does not exist). */
async function ensureImage(ctx, source, target) {
  const digest = await ctx.registry.digest(source);
  if (digest === null) {
    ctx.log(`pas d'image ${source} : ${target} ignoré`);
    return "noImage";
  }
  const existing = await ctx.registry.digest(target);
  if (existing === digest) return "skipped";
  if (existing !== null && !target.endsWith(`:${BETA_CHANNEL}`)) {
    throw new Error(`${target} existe déjà sur ${existing}, pas sur ${digest} (${source})`);
  }
  await write(ctx, `image ${target} <- ${source} (${digest})`, () => ctx.registry.tag(source, target));
  return "tagged";
}

async function create(plan, ctx) {
  await disableWorkflows(ctx, "Création refusée");
  const counts = { tags: 0, releases: 0, images: 0, noImage: 0 };
  const countImage = (result) => {
    if (result === "tagged") counts.images++;
    if (result === "noImage") counts.noImage++;
  };
  for (const entry of plan) {
    if (await ensureTag(ctx, entry)) counts.tags++;
    if (await ensureRelease(ctx, entry, plan)) counts.releases++;
    if (!entry.name) continue;
    for (const image of IMAGES) {
      countImage(await ensureImage(ctx, `${image}:${entry.name}`, `${image}:${imageTag(entry.newName)}`));
    }
  }
  const last = plan.at(-1);
  for (const image of IMAGES) countImage(await ensureImage(ctx, `${image}:${last.name}`, `${image}:${BETA_CHANNEL}`));

  const done = ctx.dryRun ? "à créer" : "créés";
  ctx.log(
    `Résumé : ${counts.tags} tags, ${counts.releases} releases, ${counts.images} tags d'image ${done} ` +
      `(${counts.noImage} sans image source). Les workflows restent désactivés jusqu'à une vérification réussie.`,
  );
  return { counts };
}

// --- verify / restore ---

async function imageCheck(ctx, source, target) {
  const digest = await ctx.registry.digest(source);
  if (digest === null) return { ok: true, cell: "pas d'image" };
  const actual = await ctx.registry.digest(target);
  if (actual === digest) return { ok: true, cell: "ok" };
  return {
    ok: false,
    cell: actual === null ? "MANQUANTE" : "AUTRE DIGEST",
    problem: `${target} : ${actual ?? "absente"}, attendu ${digest} (${source})`,
  };
}

async function verify(plan, ctx) {
  const rows = [];
  const problems = [];
  for (const entry of plan) {
    const tagSha = await ctx.gh.getTag(entry.newName);
    const tagOk = tagSha === entry.sha;
    if (!tagOk) {
      problems.push(`tag ${entry.newName} : ${tagSha ? short(tagSha) : "absent"}, attendu ${short(entry.sha)}`);
    }
    const release = await ctx.gh.getReleaseByTag(entry.newName);
    const releaseOk = release !== null && release.prerelease === (entry.name !== null);
    if (!releaseOk) problems.push(`release ${entry.newName} : ${release ? "mauvais type de release" : "absente"}`);
    const images = [];
    for (const image of IMAGES) {
      if (!entry.name) {
        images.push("—");
        continue;
      }
      const check = await imageCheck(ctx, `${image}:${entry.name}`, `${image}:${imageTag(entry.newName)}`);
      if (!check.ok) problems.push(check.problem);
      images.push(check.cell);
    }
    const status = (ok) => (ok ? "ok" : "ÉCHEC");
    rows.push([entry.newName, entry.name ?? "—", short(entry.sha), status(tagOk), status(releaseOk), ...images]);
  }
  const last = plan.at(-1);
  const beta = [];
  for (const image of IMAGES) {
    const check = await imageCheck(ctx, `${image}:${last.name}`, `${image}:${BETA_CHANNEL}`);
    if (!check.ok) problems.push(check.problem);
    beta.push(check.cell);
  }
  rows.push([`:${BETA_CHANNEL}`, last.name, "—", "—", "—", ...beta]);

  const passed = problems.length === 0;
  const report = verifyReport(plan, rows, problems, ctx.dryRun);
  ctx.log(
    passed
      ? `Vérification réussie (${plan.length} versions).`
      : `Vérification en échec : ${problems.length} problème(s).`,
  );
  for (const problem of problems) ctx.log(`- ${problem}`);
  if (ctx.dryRun) {
    ctx.log("[simulation] résultat non enregistré : relance avec --execute pour autoriser la suppression.");
  } else {
    updateState(ctx, (state) => {
      state.verify = { planHash: planHash(plan), passed, at: new Date().toISOString() };
    });
  }
  if (passed) await restore(ctx);
  return { passed, report };
}

function verifyReport(plan, rows, problems, dryRun) {
  const table = [
    "| Nouveau tag | Ancien tag | Commit | Tag | Release | api | web |",
    "|---|---|---|---|---|---|---|",
    ...rows.map((row) => `| ${row.join(" | ")} |`),
  ];
  return [
    "# Migration des versions : vérification",
    "",
    `- Date : ${new Date().toISOString()}${dryRun ? " (simulation, non enregistrée)" : ""}`,
    `- Plan : \`${planHash(plan)}\` (${plan.length} versions)`,
    `- Résultat : **${problems.length ? `échec, ${problems.length} problème(s)` : "validé"}**`,
    "",
    ...table,
    ...(problems.length ? ["", "## Problèmes", "", ...problems.map((p) => `- ${p}`)] : []),
    "",
  ].join("\n");
}

async function restore(ctx) {
  const ids = ctx.store.read().disabledWorkflows ?? [];
  if (ids.length === 0) ctx.log("Aucun workflow désactivé par la migration à réactiver.");
  for (const id of ids) {
    await write(ctx, `réactivation du workflow ${id}`, async () => {
      await ctx.gh.enableWorkflow(id);
      updateState(ctx, (state) => {
        state.disabledWorkflows = state.disabledWorkflows.filter((other) => other !== id);
      });
    });
  }
  return {};
}

// --- delete ---

async function remove(plan, ctx) {
  const { verify: last } = ctx.store.read();
  if (!last?.passed || last.planHash !== planHash(plan)) {
    throw new Error(
      "Suppression refusée : la dernière vérification enregistrée n'a pas validé ce plan exact " +
        "(lance --phase verify --execute).",
    );
  }
  await disableWorkflows(ctx, "Suppression refusée");
  const deleted = [];
  const kept = [];
  for (const entry of plan.filter((e) => e.name)) {
    const replacement = await ctx.gh.getTag(entry.newName);
    if (replacement !== entry.sha) {
      throw new Error(`Suppression interrompue : ${entry.newName} n'est plus sur ${short(entry.sha)}`);
    }
    const release = await ctx.gh.getReleaseByTag(entry.name);
    if (release !== null) {
      if ((await ctx.gh.getReleaseByTag(entry.newName)) === null) {
        throw new Error(`Suppression interrompue : la release ${entry.newName} n'existe plus`);
      }
      await write(ctx, `suppression de la release ${entry.name}`, () => ctx.gh.deleteRelease(release.id));
      deleted.push(`release \`${entry.name}\``);
    }
    if ((await ctx.gh.getTag(entry.name)) !== null) {
      await write(ctx, `suppression du tag ${entry.name}`, () => ctx.gh.deleteTag(entry.name));
      deleted.push(`tag \`${entry.name}\``);
    }
    const branch = `v/${entry.name}`;
    if ((await ctx.gh.getBranch(branch)) !== null) {
      await write(ctx, `suppression de la branche ${branch}`, () => ctx.gh.deleteBranch(branch));
      deleted.push(`branche \`${branch}\``);
    }
    for (const image of IMAGES) {
      const ref = `${image}:${entry.name}`;
      if ((await ctx.registry.digest(ref)) !== null) kept.push(ref);
    }
  }
  const done = ctx.dryRun ? "à supprimer" : "supprimés";
  ctx.log(`${deleted.length} objets ${done}, ${kept.length} tags d'image conservés.`);
  await restore(ctx);
  return { report: deleteReport(plan, deleted, kept, ctx.dryRun) };
}

function deleteReport(plan, deleted, kept, dryRun) {
  return [
    `# Migration des versions : suppression${dryRun ? " (simulation)" : ""}`,
    "",
    `- Date : ${new Date().toISOString()}`,
    `- Plan : \`${planHash(plan)}\` (${plan.length} versions)`,
    "",
    `## ${dryRun ? "À supprimer" : "Supprimés"} (${deleted.length})`,
    "",
    ...deleted.map((d) => `- ${d}`),
    "",
    `## Tags d'image conservés (${kept.length})`,
    "",
    "GHCR ne sait retirer un tag qu'en supprimant la version (le digest) qu'il désigne, qui porte aussi le " +
      "nouveau tag : les anciens tags d'image sont donc conservés, aucun digest n'est supprimé. `:latest` " +
      "n'est pas modifié.",
    "",
    ...kept.map((ref) => `- \`${ref}\``),
    "",
  ].join("\n");
}

// --- changelog ---

/** The CHANGELOG.md of the planned versions; deps: {gh ({compareCommits}), dateOf(sha) -> YYYY-MM-DD}. */
export async function changelogFromPlan(plan, { gh, dateOf }) {
  const ctx = { gh };
  const releases = [];
  for (const entry of plan) {
    releases.push({ tag: entry.newName, date: dateOf(entry.sha), body: await releaseBody(ctx, entry, plan) });
  }
  return renderChangelogFile(releases);
}

// --- entry points ---

function printPlan(plan, ctx) {
  for (const [i, entry] of plan.entries()) {
    const since = i > 1 && entry.base !== plan[i - 1].newName ? `, changelog depuis ${entry.base}` : "";
    const flag = entry.nonLinear ? " (historique non linéaire)" : "";
    ctx.log(`${entry.newName} <- ${entry.name ?? "(commit initial)"} ${short(entry.sha)}${since}${flag}`);
  }
  ctx.log(`${plan.length} versions (${plan.length - 1} anciens tags).`);
  return {};
}

/**
 * Runs one phase of the migration. deps: {gh, registry, store ({read, write} of the local state),
 * dryRun (default true), log, dateOf (changelog only)}. -> {counts?, passed?, report? (Markdown), changelog?}.
 */
export async function runPhase(phase, plan, { gh, registry, store, dryRun = true, log = console.log, dateOf }) {
  const ctx = { gh, registry, store, dryRun, log, dateOf };
  switch (phase) {
    case "plan":
      return printPlan(plan, ctx);
    case "create":
      return create(plan, ctx);
    case "verify":
      return verify(plan, ctx);
    case "restore":
      return restore(ctx);
    case "delete":
      return remove(plan, ctx);
    case "changelog":
      return { changelog: await changelogFromPlan(plan, { gh, dateOf: ctx.dateOf }) };
    default:
      throw new Error(`Phase inconnue : ${phase} (${PHASES.join(", ")})`);
  }
}

/** The local tags as {name, sha (commit, annotated tags dereferenced), created}. */
export function parseTagRefs(output) {
  return output
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      const [name, object, peeled, created] = line.split("\t");
      return { name, sha: peeled || object, created };
    });
}

/** `git merge-base --is-ancestor`: exit 0 = ancestor, 1 = not, anything else is an error. */
function gitIsAncestor(ancestor, sha) {
  try {
    execFileSync("git", ["merge-base", "--is-ancestor", ancestor, sha], { stdio: "ignore" });
    return true;
  } catch (error) {
    if (error.status === 1) return false;
    throw error;
  }
}

/** compareCommits from the local history, oldest first, as the GitHub client gives it. */
const localGit = {
  async compareCommits(base, head) {
    const out = execFileSync("git", ["log", "--reverse", "--format=%H%x09%s", `${base}..${head}`], { encoding: "utf8" });
    return out
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const [sha, ...subject] = line.split("\t");
        return { sha, subject: subject.join("\t") };
      });
  },
};

const commitDate = (sha) => execFileSync("git", ["log", "-1", "--format=%cs", sha], { encoding: "utf8" }).trim();

function fileStore(path) {
  return {
    read: () => (existsSync(path) ? JSON.parse(readFileSync(path, "utf8")) : {}),
    write: (state) => writeFileSync(path, `${JSON.stringify(state, null, 2)}\n`),
  };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const { values } = parseArgs({
    options: {
      phase: { type: "string" },
      execute: { type: "boolean", default: false },
      "dry-run": { type: "boolean", default: false },
    },
  });
  if (!PHASES.includes(values.phase)) {
    throw new Error(`Usage: migrate.mjs --phase ${PHASES.join("|")} [--execute]`);
  }
  if (values.execute && values["dry-run"]) throw new Error("--execute et --dry-run sont incompatibles");

  const exec = promisify(execFile);
  const { stdout } = await exec("git", [
    "for-each-ref",
    "refs/tags",
    "--sort=creatordate",
    "--format=%(refname:short)%09%(objectname)%09%(*objectname)%09%(creatordate:iso-strict)",
  ]);
  const plan = planMigration(parseTagRefs(stdout), INIT_COMMIT, { isAncestor: gitIsAncestor });
  if (plan.length !== LEGACY_TAG_COUNT + 1) {
    throw new Error(
      `${plan.length - 1} anciens tags trouvés localement, ${LEGACY_TAG_COUNT} attendus (git fetch --tags ?)`,
    );
  }

  const dryRun = !values.execute;
  const simulated = dryRun && !["plan", "changelog"].includes(values.phase);
  console.log(`Phase ${values.phase}${simulated ? " (simulation : rien n'est écrit, --execute pour agir)" : ""}`);
  const deps = { store: fileStore(STATE_FILE), dryRun };
  if (values.phase === "changelog") {
    deps.gh = localGit;
    deps.dateOf = commitDate;
  } else if (values.phase !== "plan") {
    const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? "enimaloc/catapult").split("/");
    deps.gh = createGitHub({ token: process.env.GH_TOKEN, owner, repo });
    deps.registry = createRegistry({ exec });
  }
  const result = await runPhase(values.phase, plan, deps);
  if (result.report) {
    writeFileSync(reportFile(values.phase), result.report);
    console.log(`Rapport : ${reportFile(values.phase)}`);
  }
  if (result.changelog) {
    const path = fileURLToPath(new URL(`../../${CHANGELOG_FILE}`, import.meta.url));
    writeFileSync(path, result.changelog);
    console.log(`${CHANGELOG_FILE} : ${path}`);
  }
  if (result.passed === false) process.exitCode = 1;
}
