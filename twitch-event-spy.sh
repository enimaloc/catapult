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
CHANNELS=()
VERBOSE=false
WS_PID=""
declare -A BROADCASTER_IDS
declare -A BROADCASTER_NAMES
declare -A PREV_GAME
declare -A PREV_TITLE
declare -A PREV_CCLS

# ── Help ───────────────────────────────────────────────────────────────────
usage() {
  cat <<EOF
Usage: $(basename "$0") <channel> [channel2 ...] [-v] [--help]

Arguments:
  <channel>    Twitch channel login name(s) (e.g. ninja pokimane xqc)

Options:
  -v           Verbose mode — also prints raw JSON for each event
  --help       Show this message and exit

Dependencies: twitch-cli, websocat, jq
Pre-requisite: run 'twitch token -u -s user:read:email' before first use
               (WebSocket EventSub requires a user access token, not app token)
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
      *)      CHANNELS+=("$1") ;;
    esac
    shift
  done
  if [[ ${#CHANNELS[@]} -eq 0 ]]; then
    echo "Error: at least one channel name required" >&2; usage; exit 1
  fi
}

# ── Cleanup ────────────────────────────────────────────────────────────────
cleanup() {
  [[ -n "$WS_PID" ]] && kill "$WS_PID" 2>/dev/null || true
  exec 3>&- 2>/dev/null || true
  [[ -p "$FIFO" ]] && rm -f "$FIFO"
  echo -e "\n${BOLD}Disconnected.${RESET}"
}

# ── Resolve broadcaster ID ─────────────────────────────────────────────────
get_broadcaster_id() {
  local login="$1"
  local response id
  response=$(twitch api get /users -q "login=$login" 2>/dev/null)
  id=$(echo "$response" | jq -r '.data[0].id // empty')
  if [[ -z "$id" ]]; then
    echo "Error: channel '$login' not found on Twitch" >&2
    exit 1
  fi
  echo "$id"
}

# ── Fetch current game/title to seed diff state ────────────────────────────
fetch_initial_state() {
  local broadcaster_id="$1"
  local response
  response=$(twitch api get /channels -q "broadcaster_id=$broadcaster_id" 2>/dev/null)
  PREV_GAME["$broadcaster_id"]=$(echo "$response" | jq -r '.data[0].game_name // ""')
  PREV_TITLE["$broadcaster_id"]=$(echo "$response" | jq -r '.data[0].title // ""')
  PREV_CCLS["$broadcaster_id"]=$(echo "$response" | jq -r '.data[0].content_classification_labels // [] | sort | join(",")')
}

# ── Format and display notification events ─────────────────────────────────
format_event() {
  local payload="$1"
  local sub_type ts event bid login

  sub_type=$(echo "$payload" | jq -r '.metadata.subscription_type')
  ts=$(date '+%H:%M:%S')
  event=$(echo "$payload" | jq -r '.payload.event')
  bid=$(echo "$payload" | jq -r '.payload.event.broadcaster_user_id')
  login="${BROADCASTER_NAMES[$bid]:-unknown}"
  [[ -z "${BROADCASTER_NAMES[$bid]+set}" ]] && return

  if [[ "$VERBOSE" == true ]]; then
    echo "$payload" | jq '.'
  fi

  case "$sub_type" in
    "stream.online")
      echo -e "[${ts}] [${login}] ${GREEN}${BOLD}🟢 STREAM ONLINE${RESET}"
      ;;

    "stream.offline")
      echo -e "[${ts}] [${login}] ${RED}${BOLD}🔴 STREAM OFFLINE${RESET}"
      ;;

    "channel.update")
      local game title ccls
      game=$(echo  "$event" | jq -r '.category_name // ""')
      title=$(echo "$event" | jq -r '.title // ""')
      ccls=$(echo  "$event" | jq -r '.content_classification_labels // [] | sort | join(",")')

      if [[ "$game" != "${PREV_GAME[$bid]:-}" ]]; then
        echo -e "[${ts}] [${login}] ${YELLOW}${BOLD}🎮 GAME CHANGE${RESET}     ${PREV_GAME[$bid]:-<none>} ${BOLD}→${RESET} ${BOLD}${game}${RESET}"
        PREV_GAME["$bid"]="$game"
      fi
      if [[ "$title" != "${PREV_TITLE[$bid]:-}" ]]; then
        echo -e "[${ts}] [${login}]    TITLE CHANGE    \"${PREV_TITLE[$bid]:-}\" ${BOLD}→${RESET} \"${BOLD}${title}${RESET}\""
        PREV_TITLE["$bid"]="$title"
      fi
      if [[ "$ccls" != "${PREV_CCLS[$bid]:-}" ]]; then
        echo -e "[${ts}] [${login}] ${CYAN}${BOLD}🏷  CCL CHANGE${RESET}      ${PREV_CCLS[$bid]:-<none>} ${BOLD}→${RESET} ${BOLD}${ccls:-<none>}${RESET}"
        PREV_CCLS["$bid"]="$ccls"
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
      if echo "$out" | grep -q "invalid transport and auth combination"; then
        echo -e "  ${YELLOW}→ Hint: run 'twitch token -u -s user:read:email' to get a user access token${RESET}" >&2
      fi
    fi
  done
}

main() {
  [[ "${1:-}" == "--help" ]] && { usage; exit 0; }
  check_deps
  parse_args "$@"

  for channel in "${CHANNELS[@]}"; do
    echo -e "${BOLD}Resolving channel:${RESET} ${CYAN}${channel}${RESET}"
    local bid
    bid=$(get_broadcaster_id "$channel")
    BROADCASTER_IDS["$channel"]="$bid"
    BROADCASTER_NAMES["$bid"]="$channel"
    fetch_initial_state "$bid"
    echo -e "${BOLD}ID:${RESET} ${bid} | ${BOLD}Game:${RESET} ${PREV_GAME[$bid]:-<none>} | ${BOLD}Title:${RESET} ${PREV_TITLE[$bid]:-<none>} | ${BOLD}CCLs:${RESET} ${PREV_CCLS[$bid]:-<none>}"
  done

  mkfifo "$FIFO"
  exec 3>"$FIFO"
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
        for bid in "${BROADCASTER_IDS[@]}"; do
          subscribe_events "$session_id" "$bid"
        done
        local channels_str
        channels_str=$(printf '%s,' "${CHANNELS[@]}" | sed 's/,$//')
        echo -e "${GREEN}${BOLD}Listening for events on ${CYAN}${channels_str}${GREEN}...${RESET} (Ctrl+C to stop)"
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
