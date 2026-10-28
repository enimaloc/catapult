// Version computation for the dev -> beta -> main release flow.
// Tags are `vX.Y.Z` (stable) and `vX.Y.Z-BETA.N` (pre-release).

const TAG = /^v(\d+)\.(\d+)\.(\d+)(?:-BETA\.(\d+))?$/;
const SUBJECT = /^(\w+)(?:\([^)]*\))?(!)?:/;

export function parseVersion(tag) {
  const m = TAG.exec(tag);
  if (!m) throw new Error(`Invalid version tag: ${tag}`);
  return {
    major: Number(m[1]),
    minor: Number(m[2]),
    patch: Number(m[3]),
    beta: m[4] === undefined ? null : Number(m[4]),
  };
}

function format({ major, minor, patch }, beta) {
  const base = `v${major}.${minor}.${patch}`;
  return beta === undefined ? base : `${base}-BETA.${beta}`;
}

function compare(a, b) {
  return a.major - b.major || a.minor - b.minor || a.patch - b.patch;
}

/**
 * Highest bump required by conventional commit messages, or null when none applies.
 * On a 0.x base (`lastStable`), a breaking change only bumps the minor.
 */
export function bumpKind(subjects, lastStable = null) {
  const preMajor = lastStable !== null && parseVersion(lastStable).major === 0;
  let kind = null;
  for (const message of subjects) {
    const m = SUBJECT.exec(message.split("\n")[0]);
    if (!m) continue;
    let current;
    if (m[2] || /^BREAKING[ -]CHANGE:/m.test(message)) current = preMajor ? "minor" : "major";
    else current = m[1] === "feat" ? "minor" : "patch";
    if (rank(current) > rank(kind)) kind = current;
  }
  return kind;
}

function rank(kind) {
  return { patch: 1, minor: 2, major: 3 }[kind] ?? 0;
}

function bump(version, kind) {
  if (kind === "major") return { major: version.major + 1, minor: 0, patch: 0 };
  if (kind === "minor") return { major: version.major, minor: version.minor + 1, patch: 0 };
  return { major: version.major, minor: version.minor, patch: version.patch + 1 };
}

/**
 * Next beta tag: the target is the higher of lastStable bumped by the commits and the highest
 * target among the betas newer than lastStable; N continues that target's line, or restarts at 1.
 */
export function nextBeta(lastStable, betaTags, subjects) {
  const stable = parseVersion(lastStable);
  const betas = betaTags
    .map(parseVersion)
    .filter((v) => v.beta !== null && compare(v, stable) > 0);
  const kind = bumpKind(subjects, lastStable);
  if (kind === null && betas.length === 0) {
    throw new Error("Nothing to release: no releasable commit and no existing beta");
  }
  let target = kind === null ? null : bump(stable, kind);
  for (const beta of betas) {
    if (target === null || compare(beta, target) > 0) target = beta;
  }
  const sameTarget = betas.filter((v) => compare(v, target) === 0).map((v) => v.beta);
  const n = sameTarget.length ? Math.max(...sameTarget) + 1 : 1;
  return format(target, n);
}

export function nextStable(betaTag) {
  const version = parseVersion(betaTag);
  if (version.beta === null) throw new Error(`Not a beta tag: ${betaTag}`);
  return format(version);
}
