#!/usr/bin/env bash
# Post-deploy smoke for the catapult WebSocket stack.
# Runs four cheap checks; exits non-zero on the first failure.
#
# Usage:
#   ./e2e/smoke.sh
#   BASE_URL=https://catapult.example.com ./e2e/smoke.sh
#
# Requires: curl, websocat (only for step 3 & 4). `docker compose` is
# needed for step 4 (Redis publish).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost}"
WS_URL="${BASE_URL/http/ws}/ws"

echo "── catapult smoke ─────────────────────────────────────"
echo "Base URL : ${BASE_URL}"
echo

# ── 1. Health endpoint ────────────────────────────────────────────────
echo "1. /actuator/health"
curl -fsS "${BASE_URL}/actuator/health" | head -c 200
echo
echo

# ── 2. Front loads ────────────────────────────────────────────────────
echo "2. Front loads"
status=$(curl -fsS -o /dev/null -w "%{http_code}" "${BASE_URL}/")
echo "GET / → HTTP ${status}"
[ "${status}" = "200" ] || { echo "FAIL: expected 200"; exit 1; }
echo

# ── 3. WS endpoint accepts upgrade ────────────────────────────────────
echo "3. WS upgrade + heartbeat"
if ! command -v websocat >/dev/null 2>&1; then
    echo "SKIP: websocat not installed (cargo install websocat or apt install websocat)"
    echo
else
    # Subscribe to events.global, wait up to 18s for a ping frame.
    # The server pings every 15s.
    output=$(printf '%s\n' '{"type":"subscribe","channel":"events.global"}' \
        | timeout 18 websocat -n1 --exit-on-eof "${WS_URL}" 2>&1 | head -3 || true)
    if echo "${output}" | grep -q '"type":"sub.ok"\|"type":"ping"'; then
        echo "OK: WS replied"
        echo "${output}" | head -3 | sed 's/^/  /'
    else
        echo "FAIL: expected sub.ok or ping frame; got:"
        echo "${output}" | sed 's/^/  /'
        exit 1
    fi
    echo
fi

# ── 4. Redis publish reaches the WS ───────────────────────────────────
echo "4. Redis publish → WS fanout"
if ! command -v websocat >/dev/null 2>&1; then
    echo "SKIP: websocat not installed"
elif ! command -v docker >/dev/null 2>&1; then
    echo "SKIP: docker not on PATH"
else
    tmpfile=$(mktemp)
    trap 'rm -f "${tmpfile}"' EXIT

    # Start a background WS listener subscribed to events.global.
    (
        printf '%s\n' '{"type":"subscribe","channel":"events.global"}'
        sleep 6
    ) | timeout 8 websocat --exit-on-eof "${WS_URL}" > "${tmpfile}" 2>&1 &
    WSPID=$!
    sleep 1

    # Publish a marker event.
    docker compose exec -T catapult-redis redis-cli PUBLISH \
        catapult:events:global \
        '{"name":"smoke-test","data":{"k":"v"},"ts":0}' >/dev/null

    wait "${WSPID}" 2>/dev/null || true
    if grep -q '"smoke-test"' "${tmpfile}"; then
        echo "OK: smoke-test event received via WS"
    else
        echo "FAIL: smoke-test event not received within 6s"
        echo "WS output:"
        sed 's/^/  /' "${tmpfile}"
        exit 1
    fi
fi
echo

echo "── catapult smoke OK ──────────────────────────────────"
