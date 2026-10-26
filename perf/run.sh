#!/usr/bin/env bash
# Timing run of catapult on a throwaway stack (perf/compose.yml): builds both images, starts the stack,
# checks the perf login, measures, scans the logs for outbound traffic, then always removes the stack
# (containers, networks, anonymous volumes), on success, failure or Ctrl-C alike.
#
# Usage:
#   perf/run.sh                full run: page timings (Playwright) then load (k6), then the report
#   perf/run.sh --smoke        start the stack, check the perf login, stop: no measurement
#   perf/run.sh --pages-only   page timings only
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

MODE=full
case "${1:-}" in
    "") ;;
    --smoke) MODE=smoke ;;
    --pages-only) MODE=pages ;;
    --load-only) MODE=load ;;
    *) echo "Usage: $0 [--smoke|--pages-only|--load-only]" >&2; exit 2 ;;
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
# Page timings (Playwright, e2e/tests/perf): writes ${REPORT_DIR}/pages.json.
run_pages() {
    echo "Page timings (headless Chromium)"
    [[ -d "${REPO_DIR}/e2e/node_modules" ]] || run npm --prefix "${REPO_DIR}/e2e" install
    local status=0
    (
        cd "${REPO_DIR}/e2e"
        PERF_BASE_URL="${BASE_URL}" PERF_RESULTS_DIR="${REPORT_DIR}" \
            exec npx playwright test --project=perf
    ) &
    child=$!
    wait "${child}" || status=$?
    child=""
    [[ -f "${REPORT_DIR}/pages.json" ]] && echo "Wrote ${REPORT_DIR}/pages.json"
    return "${status}"
}

# Load (k6, task 9): writes ${REPORT_DIR}/k6-summary.json and ${REPORT_DIR}/docker-stats.csv.
run_load() {
    echo "TODO: load (k6) is not wired yet"
}

# Report (task 9): writes ${REPORT_DIR}/report.md and ${REPORT_DIR}/report.json, with the
# "Outbound traffic" section from ${REPORT_DIR}/outbound.txt.
write_report() {
    echo "TODO: report is not wired yet"
}

# ── Main ─────────────────────────────────────────────────────────────────
cd "${REPO_DIR}"
start_stack
check_login
if [[ "${MODE}" != smoke ]]; then
    settle
    [[ "${MODE}" == full || "${MODE}" == pages ]] && run_pages
    [[ "${MODE}" == full || "${MODE}" == load ]] && run_load
fi
scan_outbound
[[ "${MODE}" != smoke ]] && write_report
echo "Done: ${REPORT_DIR}"
