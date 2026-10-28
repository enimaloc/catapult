#!/usr/bin/env bash
# Timing run of catapult on a throwaway stack (perf/compose.yml): builds both images, starts the stack,
# checks the perf login, measures, scans the logs for outbound traffic, then always removes the stack
# (containers, networks, anonymous volumes), on success, failure or Ctrl-C alike.
#
# Usage:
#   perf/run.sh                full run: page timings and DOM mutations (Playwright) then load (k6),
#                              then the report
#   perf/run.sh --smoke        start the stack, check the perf login, stop: no measurement
#   perf/run.sh --pages-only   page timings and DOM mutations only
#   perf/run.sh --dom-only     DOM mutations only
#   perf/run.sh --load-only    load only
#
# Environment:
#   STREAMERS (100)   seeded perf-streamer-NNN accounts, all live
#   VIEWERS (1000)    load: concurrent viewers
#   DURATION (30m)    load: duration
#   WEB_PORT (18081)  the site's port, on 127.0.0.1 only
#   API_SENTRY_DSN, SENTRY_DSN, SENTRY_TRACES_SAMPLE_RATE  empty = Sentry off
#
# Writes perf/reports/<timestamp>/ (logs.txt, outbound.txt, then the measurement files and the report).

set -euo pipefail

readonly PROJECT=catapult-verify-perf
PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PERF_DIR
readonly REPO_DIR="${PERF_DIR%/perf}"

# The first live-stream detection poll (30 s after startup) puts a category on every seeded streamer:
# measuring before it would time pages that are not in their steady state.
readonly SETTLE_SECONDS=35
# The latest k6 1.x at least two weeks old when chosen (1.8.2 came out on 2026-10-24)
readonly K6_IMAGE=grafana/k6:1.8.1
readonly K6_CONTAINER="${PROJECT}-k6"

MODE=full
case "${1:-}" in
    "") ;;
    --smoke) MODE=smoke ;;
    --pages-only) MODE=pages ;;
    --dom-only) MODE=dom ;;
    --load-only) MODE=load ;;
    *) echo "Usage: $0 [--smoke|--pages-only|--dom-only|--load-only]" >&2; exit 2 ;;
esac

export STREAMERS="${STREAMERS:-100}"
export VIEWERS="${VIEWERS:-1000}"
export DURATION="${DURATION:-30m}"
export WEB_PORT="${WEB_PORT:-18081}"
export API_SENTRY_DSN="${API_SENTRY_DSN:-}"
export SENTRY_DSN="${SENTRY_DSN:-}"
export BASE_URL="http://127.0.0.1:${WEB_PORT}"

# Throwaway secrets, new for each run. JWT_SECRET must start with "perf-" (catapult-api's PerfGuard).
# 16 random bytes as 32 hex characters (TOKEN_ENCRYPTION_KEY: 32 bytes exactly).
random_secret() { od -An -tx1 -N16 /dev/urandom | tr -d ' \n'; }
POSTGRES_PASSWORD="$(random_secret)"
TOKEN_ENCRYPTION_KEY="$(random_secret)"
JWT_SECRET="perf-$(random_secret)"
export POSTGRES_PASSWORD TOKEN_ENCRYPTION_KEY JWT_SECRET

COMPOSE=(docker compose -p "${PROJECT}" -f "${PERF_DIR}/compose.yml")
[[ -n "${API_SENTRY_DSN}" ]] && COMPOSE+=(-f "${PERF_DIR}/compose.sentry.yml")
# The Sentry hosts of both DSNs (https://key@host/project -> host), the outbound scan's allowed ones
SENTRY_HOSTS=()
for dsn in "${API_SENTRY_DSN}" "${SENTRY_DSN}"; do
    [[ -n "${dsn}" ]] && SENTRY_HOSTS+=("$(sed -E 's#^[a-z]+://([^@/]*@)?([^:/]+).*#\2#' <<<"${dsn}")")
done

REPORT_DIR="${PERF_DIR}/reports/$(date +%Y%m%d-%H%M%S)"
export REPORT_DIR
mkdir -p "${REPORT_DIR}"

# ── Teardown ─────────────────────────────────────────────────────────────
# Long commands run in the background and are waited for, so that a signal sent to this script alone
# (not to its process group) is handled at once instead of after the command ends.
child=""
stats_pid=""
load_status=0
pages_status=0
cleaned=0
started=0
scanned=0
cleanup() {
    [[ "${cleaned}" == 1 ]] && return
    cleaned=1
    if [[ -n "${child}" ]] && kill -0 "${child}" 2>/dev/null; then
        kill "${child}" 2>/dev/null || true
        wait "${child}" 2>/dev/null || true
    fi
    stop_stats
    # Killing docker run's client stops k6 too, unless the client was killed outright
    docker rm -f "${K6_CONTAINER}" >/dev/null 2>&1 || true
    # A run that failed or was interrupted keeps its logs: they say why
    if [[ "${started}" == 1 && "${scanned}" == 0 && -d "${REPORT_DIR:-}" ]]; then
        scan_outbound || true
    fi
    echo "Removing the ${PROJECT} stack"
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 \
        || echo "WARNING: down failed, check: docker ps -a --filter name=${PROJECT}" >&2
}
on_signal() {
    echo "Interrupted" >&2
    cleanup
    exit 130
}
trap cleanup EXIT
trap on_signal INT TERM

run() {
    "$@" &
    child=$!
    local status=0
    wait "${child}" || status=$?
    child=""
    return "${status}"
}

# ── Stack ────────────────────────────────────────────────────────────────
start_stack() {
    echo "Building the images"
    local build_at=${SECONDS}
    run "${COMPOSE[@]}" build
    echo "Built in $((SECONDS - build_at)) s"
    echo "Starting the ${PROJECT} stack (web on ${BASE_URL})"
    started=1
    local up_at=${SECONDS}
    run "${COMPOSE[@]}" up -d --wait
    echo "Healthy in $((SECONDS - up_at)) s"
}

check_login() {
    local answer status location
    answer="$(curl -s --max-time 10 -o /dev/null -w '%{http_code} %{redirect_url}' \
        "${BASE_URL}/perf/login?username=perf-streamer-001")"
    status="${answer%% *}"
    location="${answer#* }"
    echo "GET /perf/login?username=perf-streamer-001 -> ${status} ${location}"
    if [[ "${status}" != 302 || "${location}" != */channel/perf-streamer-001 ]]; then
        echo "FAIL: expected a 302 to /channel/perf-streamer-001" >&2
        return 1
    fi
}

settle() {
    echo "Waiting ${SETTLE_SECONDS} s for the first live-stream detection poll"
    run sleep "${SETTLE_SECONDS}"
}

# ── Outbound traffic ─────────────────────────────────────────────────────
# A connection error towards any host but the stack's own (db, redis, api, web, the loopback, the
# perf-internal subnet; the Sentry hosts when a DSN is set) means some client was not faked and tried the
# internet. api has no route out, so its attempts fail and show here; web has one (edge, for its published
# port), so a non-faked web client that succeeds leaves no trace: the scan only catches failures.
readonly OUTBOUND_ERRORS='UnknownHostException|UnresolvedAddressException|Failed to resolve|ConnectException'\
'|ConnectTimeoutException|Connection refused|Network is unreachable|No route to host'\
'|SocketTimeoutException|connect timed out'

is_stack_host() {
    case "$1" in
        db | redis | api | web | localhost | 127.0.0.1 | 10.251.251.*) return 0 ;;
    esac
    local sentry
    for sentry in "${SENTRY_HOSTS[@]+"${SENTRY_HOSTS[@]}"}"; do
        if [[ "$1" == "${sentry}" ]]; then
            return 0
        fi
    done
    return 1
}

# The hosts a log line names: after "UnknownHostException:" or "Failed to resolve", in a URL, or as
# host:port (host/1.2.3.4:port counts as host). The service prefix and the timestamp are left out first.
line_hosts() {
    sed -E -e 's/^[a-z]+-[0-9]+ +\| //' -e 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z?//' \
        -e 's#([A-Za-z][A-Za-z0-9.-]*)/[0-9.]+:([0-9]+)#\1:\2#g' <<<"$1" \
        | grep -oE "UnknownHostException: [A-Za-z0-9.-]+|Failed to resolve '[^']+'|://[A-Za-z0-9.-]+\
|(^|[^A-Za-z0-9._-])[A-Za-z0-9][A-Za-z0-9.-]*:[0-9]+" \
        | sed -E -e 's/^UnknownHostException: //' -e "s/^Failed to resolve '([^']+)'/\1/" \
            -e 's#^://##' -e 's/^[^A-Za-z0-9]//' -e 's/:[0-9]+$//'
}

# A line is kept unless every host it names is the stack's own; a line naming no host is kept too.
scan_outbound() {
    scanned=1
    "${COMPOSE[@]}" logs --no-color >"${REPORT_DIR}/logs.txt" 2>&1 || true
    : >"${REPORT_DIR}/outbound.txt"
    local line host hosts outside
    while IFS= read -r line; do
        hosts="$(line_hosts "${line}" || true)"
        outside=1
        if [[ -n "${hosts}" ]]; then
            outside=0
            while IFS= read -r host; do
                is_stack_host "${host}" || outside=1
            done <<<"${hosts}"
        fi
        if [[ "${outside}" == 1 ]]; then
            printf '%s\n' "${line}" >>"${REPORT_DIR}/outbound.txt"
        fi
    done < <(grep -E "${OUTBOUND_ERRORS}" "${REPORT_DIR}/logs.txt" || true)
    if [[ -s "${REPORT_DIR}/outbound.txt" ]]; then
        echo "Outbound traffic: $(wc -l <"${REPORT_DIR}/outbound.txt") line(s), see ${REPORT_DIR}/outbound.txt"
    else
        echo "Outbound traffic: none"
    fi
}

# ── Measurement hooks ────────────────────────────────────────────────────
# Page timings and DOM mutations (Playwright, e2e/tests/perf): writes ${REPORT_DIR}/pages.json and
# dom-mutations.json. Arguments: the spec files to run, all of e2e/tests/perf without any.
run_pages() {
    echo "Page timings and DOM mutations (headless Chromium)${1:+: $*}"
    [[ -d "${REPO_DIR}/e2e/node_modules" ]] || run npm --prefix "${REPO_DIR}/e2e" install
    local status=0
    (
        cd "${REPO_DIR}/e2e"
        PERF_BASE_URL="${BASE_URL}" PERF_RESULTS_DIR="${REPORT_DIR}" \
            exec npx playwright test --project=perf "$@"
    ) &
    child=$!
    wait "${child}" || status=$?
    child=""
    [[ -f "${REPORT_DIR}/pages.json" ]] && echo "Wrote ${REPORT_DIR}/pages.json"
    [[ -f "${REPORT_DIR}/dom-mutations.json" ]] && echo "Wrote ${REPORT_DIR}/dom-mutations.json"
    return "${status}"
}

# CPU and memory of the stack's containers every 5 s, as "time,container,cpu,memory" lines in $1, and the
# JVMs' own memory as "time,app,heapUsed,nonHeapUsed,heapMax" lines (bytes) in $2, until killed.
sample_stats() {
    local ids
    mapfile -t ids < <("${COMPOSE[@]}" ps -q)
    # docker stats without ids samples every container of the machine, the user's included
    if [[ "${#ids[@]}" == 0 || -z "${ids[0]}" ]]; then
        echo "WARNING: no ${PROJECT} container to sample" >&2
        return 0
    fi
    echo "time,container,cpu,memory" >"$1"
    echo "time,app,heapUsed,nonHeapUsed,heapMax" >"$2"
    while :; do
        docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' "${ids[@]}" \
            | sed "s/^/$(date +%s),/" >>"$1" || true
        jvm_memory api 8090 >>"$2" || true
        jvm_memory web 8092 >>"$2" || true
        sleep 5
    done
}

# One line of $1's JVM memory, from its actuator's Prometheus metrics on management port $2: the sums of
# jvm_memory_used_bytes over the heap and the non-heap pools, and of the heap pools' jvm_memory_max_bytes.
jvm_memory() {
    "${COMPOSE[@]}" exec -T "$1" wget -q -T 4 -O- "http://localhost:$2/actuator/prometheus" 2>/dev/null \
        | awk -v time="$(date +%s)" -v app="$1" '
            /^jvm_memory_used_bytes\{/ { if ($0 ~ /area="heap"/) heap += $NF; else nonheap += $NF }
            /^jvm_memory_max_bytes\{/ && /area="heap"/ && $NF > 0 { max += $NF }
            END { if (heap + nonheap > 0) printf "%s,%s,%.0f,%.0f,%.0f\n", time, app, heap, nonheap, max }'
}

stop_stats() {
    if [[ -n "${stats_pid}" ]] && kill -0 "${stats_pid}" 2>/dev/null; then
        kill "${stats_pid}" 2>/dev/null || true
        wait "${stats_pid}" 2>/dev/null || true
    fi
    stats_pid=""
}

# Load (k6, perf/k6/evening.js) on perf-internal, with docker stats sampled meanwhile: writes
# ${REPORT_DIR}/load.json, k6-summary.json, docker-stats.csv and jvm-memory.csv. A failed threshold fails the run, after
# the report is written.
run_load() {
    echo "Load: ${STREAMERS} streamers, ${VIEWERS} viewers, ${DURATION} (${K6_IMAGE})"
    printf '{"streamers": %s, "viewers": %s, "duration": "%s", "k6Image": "%s"}\n' \
        "${STREAMERS}" "${VIEWERS}" "${DURATION}" "${K6_IMAGE}" >"${REPORT_DIR}/load.json"
    sample_stats "${REPORT_DIR}/docker-stats.csv" "${REPORT_DIR}/jvm-memory.csv" &
    stats_pid=$!
    local status=0
    run docker run --rm --name "${K6_CONTAINER}" --network "${PROJECT}_perf-internal" \
        --user "$(id -u):$(id -g)" \
        -v "${PERF_DIR}/k6:/scripts:ro" -v "${REPORT_DIR}:/out" \
        "${K6_IMAGE}" run --quiet --summary-export /out/k6-summary.json \
        -e STREAMERS="${STREAMERS}" -e VIEWERS="${VIEWERS}" -e DURATION="${DURATION}" \
        -e WEB=http://web:8081 -e API=http://api:8080 /scripts/evening.js || status=$?
    stop_stats
    [[ -f "${REPORT_DIR}/k6-summary.json" ]] && echo "Wrote ${REPORT_DIR}/k6-summary.json"
    load_status=${status}
}

# Report (perf/report.mjs): writes ${REPORT_DIR}/report.md and report.json, with the "Outbound traffic"
# section from outbound.txt, compared with the latest earlier perf/reports/*/report.json.
write_report() {
    node "${PERF_DIR}/report.mjs" "${REPORT_DIR}"
}

# ── Main ─────────────────────────────────────────────────────────────────
cd "${REPO_DIR}"
start_stack
check_login
if [[ "${MODE}" != smoke ]]; then
    settle
    if [[ "${MODE}" == full || "${MODE}" == pages ]]; then run_pages || pages_status=$?; fi
    if [[ "${MODE}" == dom ]]; then run_pages tests/perf/dom-mutations.spec.ts || pages_status=$?; fi
    if [[ "${MODE}" == full || "${MODE}" == load ]]; then run_load; fi
fi
scan_outbound
[[ "${MODE}" != smoke ]] && write_report
echo "Done: ${REPORT_DIR}"
if [[ "${pages_status}" != 0 ]]; then
    echo "FAIL: Playwright exited with ${pages_status} (a timing or DOM mutation budget failed, or" \
        "Playwright itself did)" >&2
fi
if [[ "${load_status}" != 0 ]]; then
    echo "FAIL: k6 exited with ${load_status} (a threshold failed, or k6 itself did)" >&2
fi
if [[ "${pages_status}" != 0 ]]; then exit "${pages_status}"; fi
exit "${load_status}"
