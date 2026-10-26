# Catapult timing runs

Times catapult on a throwaway stack (`perf/compose.yml`): page timings in a headless browser
(Playwright) and a load run (k6), with seeded fake accounts and every outbound client faked
(`perf` profile). The stack is built from the working tree, started under the Compose project
`catapult-verify-perf`, and always removed at the end, Ctrl-C included.

It shares nothing with `docker-compose.yml`: no container name, named volume or network in common,
and the only published port is the site's, on `127.0.0.1`. catapult-api, Postgres and Redis sit on an
internal network with no route to the internet.

## Prerequisites

1. Docker with Compose v2 (`docker compose`), and `curl`.
2. Node.js 20+ for the page timings (Playwright, in `e2e/`).
3. A free port for the site, `18081` by default (`WEB_PORT`).

## Running

```bash
# from the project root
perf/run.sh --smoke        # start the stack, check the perf login, stop: no measurement
perf/run.sh                # full run: page timings, then load, then the report
perf/run.sh --pages-only   # page timings only
perf/run.sh --load-only    # load only

STREAMERS=50 VIEWERS=200 DURATION=5m perf/run.sh --load-only
```

| Variable                    | Default | Meaning                                         |
|-----------------------------|---------|-------------------------------------------------|
| `STREAMERS`                 | `100`   | seeded `perf-streamer-NNN` accounts, all live   |
| `VIEWERS`                   | `1000`  | load: concurrent viewers                        |
| `DURATION`                  | `30m`   | load: duration                                  |
| `WEB_PORT`                  | `18081` | the site's port, on `127.0.0.1`                 |
| `API_SENTRY_DSN`            | empty   | catapult-api's Sentry; empty = off              |
| `SENTRY_DSN`                | empty   | catapult-web's Sentry; empty = off              |
| `SENTRY_TRACES_SAMPLE_RATE` | unset   | catapult-web's trace sampling                   |

Runs are tagged `environment=perf` in Sentry. Never set `SENTRY_ENVIRONMENT` or `ENVIRONMENT`:
catapult-api refuses to start with any other environment. With `API_SENTRY_DSN`, catapult-api also
joins the `edge` network (`perf/compose.sentry.yml`) to reach Sentry.

The secrets (database password, token encryption key, `perf-` JWT key) are generated for each run.

## Duration

- Build: about 1.5 min with a warm Docker cache, longer the first time (Gradle dependencies).
- Startup to healthy: about 40 s (Flyway migrations, account seeding).
- Measurement runs wait 35 s more: the first live-stream detection poll (30 s after startup) puts a
  category on every seeded streamer, and pages are timed in that steady state.
- Load: `DURATION`, 30 min by default.

`--smoke` takes about 2 min in all.

## Reading the report

Each run writes `perf/reports/<timestamp>/` (ignored by git):

| File                | Content                                                              |
|---------------------|----------------------------------------------------------------------|
| `logs.txt`          | catapult-api and catapult-web logs                                   |
| `outbound.txt`      | connection errors towards a host outside the stack (should be empty) |
| `pages.json`        | page timings (Playwright)                                            |
| `k6-summary.json`   | k6 summary of the load run                                           |
| `docker-stats.csv`  | CPU and memory of each container during the load run                 |
| `report.md`, `.json`| the report, with its "Outbound traffic" section                      |

A line in `outbound.txt` means a client that is not faked tried to reach the internet: the internal
network refused it, but the timings around it may include its timeout.

## Cleaning up by hand

If a run was killed with `SIGKILL`, nothing removed the stack:

```bash
docker compose -p catapult-verify-perf -f perf/compose.yml down -v --remove-orphans
```

The images (`catapult-verify-perf-api`, `catapult-verify-perf-web`) are kept for the next build's cache;
`docker image rm` them to reclaim the space.
