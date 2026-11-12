// The promotion chain. Until a v2 (maybe never), `beta` is skipped: dev is promoted straight to main
// as a stable, and a release is brought back to dev straight from main.
// To bring beta back: set BETA to true here, then put `beta` back in promote.yml's push branches (the
// workflows' other conditions accept both chains).

export const BETA = false;

/** The promotions the bot opens, in release order. */
export function promotions(beta = BETA) {
  return beta
    ? [
        { source: "dev", target: "beta" },
        { source: "beta", target: "main" },
      ]
    : [{ source: "dev", target: "main" }];
}

/** The branch `source` is promoted to, or null when it is not promoted. */
export function promotionTarget(source, beta = BETA) {
  return promotions(beta).find((p) => p.source === source)?.target ?? null;
}
