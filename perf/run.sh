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
SENTRY_HOST=""
if [[ -n "${API_SENTRY_DSN}" ]]; then
    COMPOSE+=(-f "${PERF_DIR}/compose.sentry.yml")
    # https://key@host/project -> host
    SENTRY_HOST="$(sed -E 's#^[a-z]+://([^@/]*@)?([^:/]+).*#\2#' <<<"${API_SENTRY_DSN}")"
fi

REPORT_DIR="${PERF_DIR}/reports/$(date +%Y%m%d-%H%M%S)"
export REPORT_DIR
mkdir -p "${REPORT_DIR}"

# ── Teardown ─────────────────────────────────────────────────────────────
# Long commands run in the background and are waited for, so that a signal sent to this script alone
# (not to its process group) is handled at once instead of after the command ends.
child=""
cleaned=0
cleanup() {
    [[ "${cleaned}" == 1 ]] && return
    cleaned=1
    if [[ -n "${child}" ]] && kill -0 "${child}" 2>/dev/null; then
        kill "${child}" 2>/dev/null || true
        wait "${child}" 2>/dev/null || true
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
    local started=${SECONDS}
    run "${COMPOSE[@]}" build
    echo "Built in $((SECONDS - started)) s"
    echo "Starting the ${PROJECT} stack (web on ${BASE_URL})"
    started=${SECONDS}
    run "${COMPOSE[@]}" up -d --wait
    echo "Healthy in $((SECONDS - started)) s"
}

check_login() {
    local answer status location
    answer="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' \
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
# A connection error towards any host but the stack's own (db, redis, api, the loopback; the Sentry host
# when a DSN is set) means some client was not faked and tried the internet: the internal network refused it.
scan_outbound() {
    "${COMPOSE[@]}" logs --no-color api web >"${REPORT_DIR}/logs.txt" 2>&1 || true
    local allowed='db|redis|api|127\.0\.0\.1|localhost'
    [[ -n "${SENTRY_HOST}" ]] && allowed="${allowed}|${SENTRY_HOST//./\\.}"
    grep -E 'UnknownHostException|ConnectException|Connection refused' "${REPORT_DIR}/logs.txt" \
        | grep -vE "(^|[^A-Za-z0-9.-])(${allowed})([:/ ]|$)" >"${REPORT_DIR}/outbound.txt" || true
    if [[ -s "${REPORT_DIR}/outbound.txt" ]]; then
        echo "Outbound traffic: $(wc -l <"${REPORT_DIR}/outbound.txt") line(s), see ${REPORT_DIR}/outbound.txt"
    else
        echo "Outbound traffic: none"
    fi
}

# ── Measurement hooks ────────────────────────────────────────────────────
# Page timings (Playwright, task 8): writes ${REPORT_DIR}/pages.json.
run_pages() {
    echo "TODO: page timings (Playwright) are not wired yet"
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
