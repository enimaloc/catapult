# Catapult timing runs

Times catapult on a throwaway stack (`perf/compose.yml`): page timings in a headless browser
(Playwright) and a load run (k6), with seeded fake accounts and every outbound client faked
(`perf` profile). The stack is built from the working tree, started under the Compose project
`catapult-verify-perf`, and always removed at the end, Ctrl-C included.

It shares nothing with `docker-compose.yml`: no container name, named volume or network in common,
and the only published port is the site's, on `127.0.0.1`. catapult-api, Postgres and Redis sit only on
an internal network (`perf-internal`, `10.251.251.0/24`) with no route to the internet. catapult-web
is also on `edge`, for its published port, and so does have a route out.

## Prerequisites

1. Docker with Compose v2 (`docker compose`), and `curl`.
2. Node.js 20+ for the page timings (Playwright, in `e2e/`) and the report (`perf/report.mjs`).
3. A free port for the site, `18081` by default (`WEB_PORT`).
4. For the load run, the `grafana/k6:1.8.1` image (pulled on first use).

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
catapult-api and catapult-web refuse to start with any other environment. With `API_SENTRY_DSN`, catapult-api also
joins the `edge` network (`perf/compose.sentry.yml`) to reach Sentry.

The secrets (database password, token encryption key, `perf-` JWT key) are generated for each run.

## Duration

- Build: about 1.5 min with a warm Docker cache, longer the first time (Gradle dependencies).
- Startup to healthy: about 40 s (Flyway migrations, account seeding).
- Measurement runs wait 35 s more: the first live-stream detection poll (30 s after startup) puts a
  category on every seeded streamer, and pages are timed in that steady state.
- Load: `DURATION`, 30 min by default.

`--smoke` takes about 2 min in all.

## The load run

`perf/k6/evening.js`, a busy evening, in k6 on `perf-internal` (the site as `http://web:8081`, the perf
routes of catapult-api as `http://api:8080`), for `DURATION`:

- `STREAMERS` streamers: each signs in once (`/perf/login`), keeps open the two live streams its channel page
  opens (`/events/nav` and `/events/channel/{login}`, SSE: catapult has no WebSocket), loads one of its
  channel page, `/spa/channel/{login}` or `/spa/nav-channels` every 30 s ± 5 s, and plays another of ten
  games every 10 min ± 1 min (`POST /api/internal/perf/users/{login}/game`, the real detection cycle after).
- `VIEWERS` viewers: each one's overlay polls its streamer's `/api/user/game/{widgetToken}` through the site
  every 15 s.

It fails on `http_req_duration{kind:request}` p95 ≥ 500 ms (every request but the live streams, whose
duration is how long they stayed open), `http_req_failed` ≥ 1 %, or any `ws_unexpected_close` (a live
stream closed or refused before the end of the run, the server's 30 min timeout aside). Meanwhile, `docker
stats` samples each container's CPU and memory every 5 s, and each JVM's used heap and non-heap memory is
read from its actuator (`jvm_memory_used_bytes`, on the management port, through `docker compose exec`).

Both JVMs run with `-Xmx1g -XX:+UseG1GC` in a 1.5 GiB container (`perf/compose.yml`): uncapped, a JVM
sizes its heap from the host's RAM and keeps growing it, so its memory would say more about the host than
about catapult, and differ from one host to the next. The run still writes its report when it fails.

`node --test perf/report.test.mjs` tests the report.

## Reading the report

Each run writes `perf/reports/<timestamp>/` (ignored by git):

| File                | Content                                                              |
|---------------------|----------------------------------------------------------------------|
| `logs.txt`          | the stack's logs, kept also when the run fails or is interrupted     |
| `outbound.txt`      | connection errors towards a host outside the stack (should be empty) |
| `pages.json`        | page timings (Playwright)                                            |
| `load.json`         | the load run's streamers, viewers, duration and k6 image             |
| `k6-summary.json`   | k6 summary of the load run                                           |
| `docker-stats.csv`  | CPU and memory of each container during the load run                 |
| `jvm-memory.csv`    | used heap, used non-heap and max heap of each JVM during the load run |
| `report.md`, `.json`| the report, with its "Outbound traffic" section                      |

`report.md` has the thresholds, p50, p95 and errors per request group and per page, each container's
average and peak CPU (in % of one core) and memory (RSS), each JVM's peak used memory, the machine that
load takes, and the differences with the latest earlier run under the same load that has a
`report.json` (`node perf/report.mjs <run dir> [<previous run dir>]` writes it again, against another run).

The machine: vCPUs = the sustained CPU (p95 of the stack's samples after the first minute) / 70 %;
RAM = the sum over the containers of, for a JVM, max(peak RSS, peak used heap + non-heap + its native
overhead) and, for the others, their peak RSS, / 75 %. A JVM's native overhead (thread stacks, GC
structures, malloc arenas) is the median of its RSS minus its used memory, 200 MiB when not measured.

`outbound.txt` lists the log lines with a connection error (unknown host, refused, unreachable,
timed out) towards a host outside the stack, or naming no host at all: some client is not faked.
The stack's own hosts are `db`, `redis`, `api`, `web`, `localhost`, `127.0.0.1`, `10.251.251.*` and
the Sentry hosts of the DSNs. Only failures show: catapult-api has no route out, so its attempts
always fail (and the timings around them may include the timeout), but catapult-web has one, so a
web client that is not faked and reaches the internet succeeds silently and leaves no line here.

catapult-api trusts the `X-Forwarded-*` headers of `perf-internal` (`API_TRUSTED_PROXIES`), as it
trusts catapult-web's network in production: the links it builds point at the site, and on the
requests catapult-web relays (`ApiRelayController`) the client address is the viewer's. The subnet is
fixed for that; if it clashes with a network of yours, change it in `perf/compose.yml` together with
`API_TRUSTED_PROXIES`.

## Cleaning up by hand

If a run was killed with `SIGKILL`, nothing removed the stack:

```bash
docker compose -p catapult-verify-perf -f perf/compose.yml down -v --remove-orphans
```

The images (`catapult-verify-perf-api`, `catapult-verify-perf-web`) are kept for the next build's cache;
`docker image rm` them to reclaim the space.
