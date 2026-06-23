import { execFileSync } from "child_process";
import { resolve } from "path";

/**
 * Thin wrappers around `docker compose` for E2E orchestration.
 *
 * All commands run synchronously from the host where the tests live.
 * Requires the host to have docker access AND the compose stack to be up.
 *
 * We deliberately avoid `exec()`/shell strings — every command is passed as
 * a literal argv to `execFile`, so test inputs (Redis channels, JSON payloads)
 * are never interpreted by a shell.
 */

const COMPOSE_CWD =
  process.env.CATAPULT_COMPOSE_DIR ||
  resolve(__dirname, "..", "..", "..");

export interface ComposeOpts {
  /** Override the working directory (where docker-compose.yml lives). */
  cwd?: string;
  /** Timeout in ms; default 30s. */
  timeoutMs?: number;
}

function runDocker(args: string[], opts: ComposeOpts = {}): string {
  try {
    const out = execFileSync("docker", args, {
      cwd: opts.cwd || COMPOSE_CWD,
      stdio: ["ignore", "pipe", "pipe"],
      timeout: opts.timeoutMs || 30_000,
      encoding: "utf-8",
    });
    return typeof out === "string" ? out : "";
  } catch (e: any) {
    const stderr = e?.stderr?.toString?.() || "";
    const stdout = e?.stdout?.toString?.() || "";
    throw new Error(
      `docker ${args.join(" ")} failed:\nstdout: ${stdout}\nstderr: ${stderr}`
    );
  }
}

function compose(args: string[], opts: ComposeOpts = {}): string {
  return runDocker(["compose", ...args], opts);
}

/** Stop a service (SIGTERM, default 10s grace). */
export function stopService(service: string, opts: ComposeOpts = {}): void {
  compose(["stop", service], opts);
}

/** Start a service (no rebuild). */
export function startService(service: string, opts: ComposeOpts = {}): void {
  compose(["start", service], opts);
}

/** Restart a service (SIGTERM + start). */
export function restartService(service: string, opts: ComposeOpts = {}): void {
  compose(["restart", service], opts);
}

/** Hard kill a service (SIGKILL). Use sparingly — no graceful shutdown hook fires. */
export function killService(service: string, opts: ComposeOpts = {}): void {
  compose(["kill", service], opts);
}

/**
 * Publish a Redis pub/sub message to an internal channel. The `payload`
 * argument must already be a JSON string (the format produced by
 * RedisEventPublisher).
 *
 * Example:
 *   publishRedis("catapult:events:global", JSON.stringify({
 *     name: "maintenance.scheduled",
 *     data: { startsAt: "...", durationMinutes: 15, message: "..." },
 *     ts:   Date.now()
 *   }));
 */
export function publishRedis(
  channel: string,
  payload: string,
  opts: ComposeOpts = {}
): void {
  compose(
    ["exec", "-T", "catapult-redis", "redis-cli", "PUBLISH", channel, payload],
    opts
  );
}

/** Wait for a service health check to report healthy. Returns true on success. */
export function waitForHealthy(service: string, deadlineMs = 60_000): boolean {
  const start = Date.now();
  while (Date.now() - start < deadlineMs) {
    try {
      const out = compose(["ps", "--format", "json", service]);
      if (
        out.includes(`"Health":"healthy"`) ||
        out.includes('"Health": "healthy"')
      ) {
        return true;
      }
    } catch {
      // ignore transient errors while the container restarts
    }
    // synchronous wait (we are deliberately blocking the test thread)
    const waitUntil = Date.now() + 500;
    while (Date.now() < waitUntil) {
      /* spin */
    }
  }
  return false;
}
