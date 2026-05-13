#!/usr/bin/env bash
set -euo pipefail

# ── Constants ──────────────────────────────────────────────────────────────
WS_URL="wss://eventsub.wss.twitch.tv/ws"
FIFO="/tmp/twitch_ws_fifo_$$"

declare -A SUBSCRIPTIONS=(
  ["channel.update"]="2"
  ["stream.online"]="1"
  ["stream.offline"]="1"
)

# ── Colors ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ── State ──────────────────────────────────────────────────────────────────
CHANNEL=""
VERBOSE=false
WS_PID=""
PREV_GAME=""
PREV_TITLE=""

# ── Help ───────────────────────────────────────────────────────────────────
usage() {
  cat <<EOF
Usage: $(basename "$0") <channel> [-v] [--help]

Arguments:
  <channel>    Twitch channel login name (e.g. ninja)

Options:
  -v           Verbose mode — also prints raw JSON for each event
  --help       Show this message and exit

Dependencies: twitch-cli, websocat, jq
Pre-requisite: run 'twitch token -c' before first use
EOF
}

# ── Dependency check ───────────────────────────────────────────────────────
check_deps() {
  local missing=()
  for cmd in twitch websocat jq; do
    command -v "$cmd" &>/dev/null || missing+=("$cmd")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Error: missing dependencies: ${missing[*]}" >&2
    exit 1
  fi
}

# ── Argument parsing ───────────────────────────────────────────────────────
parse_args() {
  if [[ $# -eq 0 ]]; then
    usage; exit 1
  fi
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help) usage; exit 0 ;;
      -v)     VERBOSE=true ;;
      -*)     echo "Unknown option: $1" >&2; usage; exit 1 ;;
      *)      CHANNEL="$1" ;;
    esac
    shift
  done
  if [[ -z "$CHANNEL" ]]; then
    echo "Error: channel name required" >&2; usage; exit 1
  fi
}

# ── Cleanup ────────────────────────────────────────────────────────────────
cleanup() {
  [[ -n "$WS_PID" ]] && kill "$WS_PID" 2>/dev/null || true
  [[ -p "$FIFO" ]] && rm -f "$FIFO"
  echo -e "\n${BOLD}Disconnected.${RESET}"
}

# ── Resolve broadcaster ID ─────────────────────────────────────────────────
get_broadcaster_id() {
  local response
  response=$(twitch api get /users -q "login=$CHANNEL" 2>/dev/null)
  local id
  id=$(echo "$response" | jq -r '.data[0].id // empty')
  if [[ -z "$id" ]]; then
    echo "Error: channel '$CHANNEL' not found on Twitch" >&2
    exit 1
  fi
  echo "$id"
}

# ── Fetch current game/title to seed diff state ────────────────────────────
fetch_initial_state() {
  local broadcaster_id="$1"
  local response
  response=$(twitch api get /channels -q "broadcaster_id=$broadcaster_id" 2>/dev/null)
  PREV_GAME=$(echo "$response" | jq -r '.data[0].game_name // ""')
  PREV_TITLE=$(echo "$response" | jq -r '.data[0].title // ""')
}

# ── Format and display notification events ─────────────────────────────────
format_event() {
  local payload="$1"
  local sub_type ts event

  sub_type=$(echo "$payload" | jq -r '.metadata.subscription_type')
  ts=$(date '+%H:%M:%S')
  event=$(echo "$payload" | jq -r '.payload.event')

  if [[ "$VERBOSE" == true ]]; then
    echo "$payload" | jq '.'
  fi

  case "$sub_type" in
    "stream.online")
      local user
      user=$(echo "$event" | jq -r '.broadcaster_user_name')
      echo -e "[${ts}] ${GREEN}${BOLD}🟢 STREAM ONLINE${RESET}   ${BOLD}${user}${RESET}"
      ;;

    "stream.offline")
      local user
      user=$(echo "$event" | jq -r '.broadcaster_user_name')
      echo -e "[${ts}] ${RED}${BOLD}🔴 STREAM OFFLINE${RESET}  ${BOLD}${user}${RESET}"
      ;;

    "channel.update")
      local game title
      game=$(echo  "$event" | jq -r '.category_name // ""')
      title=$(echo "$event" | jq -r '.title // ""')

      if [[ "$game" != "$PREV_GAME" ]]; then
        echo -e "[${ts}] ${YELLOW}${BOLD}🎮 GAME CHANGE${RESET}     ${PREV_GAME:-<none>} ${BOLD}→${RESET} ${BOLD}${game}${RESET}"
        PREV_GAME="$game"
      fi
      if [[ "$title" != "$PREV_TITLE" ]]; then
        echo -e "[${ts}]    TITLE CHANGE    \"${PREV_TITLE}\" ${BOLD}→${RESET} \"${BOLD}${title}${RESET}\""
        PREV_TITLE="$title"
      fi
      ;;
  esac
}

# ── Subscribe to EventSub events ───────────────────────────────────────────
subscribe_events() {
  local session_id="$1"
  local broadcaster_id="$2"

  for event_type in "${!SUBSCRIPTIONS[@]}"; do
    local version="${SUBSCRIPTIONS[$event_type]}"
    local body
    body=$(jq -n \
      --arg type    "$event_type" \
      --arg version "$version" \
      --arg bid     "$broadcaster_id" \
      --arg sid     "$session_id" \
      '{"type":$type,"version":$version,"condition":{"broadcaster_user_id":$bid},"transport":{"method":"websocket","session_id":$sid}}')
    local out
    if out=$(twitch api post /eventsub/subscriptions -b "$body" 2>&1); then
      echo -e "  ${CYAN}↳ subscribed:${RESET} ${event_type} v${version}"
    else
      echo -e "  ${RED}✗ failed:${RESET} ${event_type} — ${out}" >&2
    fi
  done
}

main() {
  [[ "${1:-}" == "--help" ]] && { usage; exit 0; }
  check_deps
  parse_args "$@"

  echo -e "${BOLD}Resolving channel:${RESET} ${CYAN}${CHANNEL}${RESET}"
  local broadcaster_id
  broadcaster_id=$(get_broadcaster_id)
  fetch_initial_state "$broadcaster_id"
  echo -e "${BOLD}ID:${RESET} ${broadcaster_id} | ${BOLD}Game:${RESET} ${PREV_GAME:-<none>} | ${BOLD}Title:${RESET} ${PREV_TITLE:-<none>}"

  mkfifo "$FIFO"
  trap cleanup SIGINT SIGTERM EXIT

  websocat --no-close "$WS_URL" < /dev/null > "$FIFO" &
  WS_PID=$!

  echo -e "${CYAN}Connecting to EventSub WebSocket...${RESET}"

  local session_id=""
  local msg_type
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    msg_type=$(echo "$line" | jq -r '.metadata.message_type // empty' 2>/dev/null) || continue

    case "$msg_type" in
      "session_welcome")
        session_id=$(echo "$line" | jq -r '.payload.session.id')
        echo -e "${GREEN}Session:${RESET} ${session_id}"
        subscribe_events "$session_id" "$broadcaster_id"
        echo -e "${GREEN}${BOLD}Listening for events on ${CYAN}${CHANNEL}${GREEN}...${RESET} (Ctrl+C to stop)"
        ;;
      "session_keepalive")
        ;;
      "session_reconnect")
        local new_url
        new_url=$(echo "$line" | jq -r '.payload.session.reconnect_url')
        echo -e "${YELLOW}⚠ Reconnect requested — switching URL...${RESET}"
        kill "$WS_PID" 2>/dev/null || true
        websocat --no-close "$new_url" < /dev/null > "$FIFO" &
        WS_PID=$!
        ;;
      "notification")
        format_event "$line"
        ;;
      *)
        ;;
    esac
  done < "$FIFO"

  echo "WebSocket closed." >&2
  exit 1
}

main "$@"
