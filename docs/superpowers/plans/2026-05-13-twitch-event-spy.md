# Twitch Event Spy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a single bash script `twitch-event-spy.sh` at the repo root that connects to Twitch EventSub WebSocket and logs channel events (game changes, stream online/offline) in real-time.

**Architecture:** `websocat` opens the EventSub WebSocket and writes JSON messages to a PID-scoped FIFO; the main script reads from the FIFO line-by-line, dispatches on message type, and subscribes to events via `twitch-cli` after receiving the `session_welcome`. State for game/title is maintained in bash globals to show diffs on `channel.update`.

**Tech Stack:** bash 4+, twitch-cli, websocat, jq

---

## Pre-requisite

`twitch-cli` must be configured and have a valid app access token:
```bash
twitch configure          # enter Client ID + Secret once
twitch token -c           # obtain app access token
```

---

## File Structure

| File | Role |
|------|------|
| `twitch-event-spy.sh` | Single-file CLI script — all logic lives here |

---

### Task 1: Script skeleton — shebang, strict mode, constants, help, dependency check, argument parsing

**Files:**
- Create: `twitch-event-spy.sh`

- [ ] **Step 1: Create the file**

```bash
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

main() {
  check_deps
  parse_args "$@"
  echo "TODO"
}

main "$@"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x twitch-event-spy.sh
```

- [ ] **Step 3: Verify shellcheck passes with no errors**

```bash
shellcheck twitch-event-spy.sh
```

Expected: no output (exit 0).

- [ ] **Step 4: Verify help output works**

```bash
./twitch-event-spy.sh --help
```

Expected:
```
Usage: twitch-event-spy.sh <channel> [-v] [--help]
...
```

- [ ] **Step 5: Verify missing arg exits cleanly**

```bash
./twitch-event-spy.sh 2>&1; echo "exit: $?"
```

Expected: prints usage, `exit: 1`.

---

### Task 2: Broadcaster ID resolution and initial channel state fetch

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1: Add `get_broadcaster_id` and `fetch_initial_state` functions — insert before `main()`**

```bash
# ── Resolve broadcaster ID ─────────────────────────────────────────────────
get_broadcaster_id() {
  local response
  response=$(twitch api get /helix/users -q "login=$CHANNEL" 2>/dev/null)
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
  response=$(twitch api get /helix/channels -q "broadcaster_id=$broadcaster_id" 2>/dev/null)
  PREV_GAME=$(echo "$response" | jq -r '.data[0].game_name // ""')
  PREV_TITLE=$(echo "$response" | jq -r '.data[0].title // ""')
}
```

- [ ] **Step 2: Replace the `main()` body**

```bash
main() {
  check_deps
  parse_args "$@"

  echo -e "${BOLD}Resolving channel:${RESET} ${CYAN}${CHANNEL}${RESET}"
  local broadcaster_id
  broadcaster_id=$(get_broadcaster_id)
  fetch_initial_state "$broadcaster_id"
  echo -e "${BOLD}Channel ID:${RESET} ${broadcaster_id}"
  echo -e "${BOLD}Current game:${RESET} ${PREV_GAME:-<none>}  ${BOLD}Title:${RESET} ${PREV_TITLE:-<none>}"
}
```

- [ ] **Step 3: Run shellcheck**

```bash
shellcheck twitch-event-spy.sh
```

Expected: exit 0, no output.

- [ ] **Step 4: Test resolution with a real channel**

```bash
./twitch-event-spy.sh ninja
```

Expected:
```
Resolving channel: ninja
Channel ID: 19571641
Current game: ...  Title: ...
```

If `Channel ID` prints correctly, the API call works.

---

### Task 3: FIFO setup, cleanup trap, websocat launch, and bare event loop

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1: Add `cleanup` function — insert after state globals**

```bash
# ── Cleanup ────────────────────────────────────────────────────────────────
cleanup() {
  [[ -n "$WS_PID" ]] && kill "$WS_PID" 2>/dev/null || true
  [[ -p "$FIFO" ]] && rm -f "$FIFO"
  echo -e "\n${BOLD}Disconnected.${RESET}"
}
```

- [ ] **Step 2: Replace `main()` body with FIFO + websocat + bare loop**

```bash
main() {
  check_deps
  parse_args "$@"

  echo -e "${BOLD}Resolving channel:${RESET} ${CYAN}${CHANNEL}${RESET}"
  local broadcaster_id
  broadcaster_id=$(get_broadcaster_id)
  fetch_initial_state "$broadcaster_id"
  echo -e "${BOLD}ID:${RESET} ${broadcaster_id} | ${BOLD}Game:${RESET} ${PREV_GAME:-<none>} | ${BOLD}Title:${RESET} ${PREV_TITLE:-<none>}"

  mkfifo "$FIFO"
  trap cleanup SIGINT SIGTERM EXIT

  websocat "$WS_URL" < /dev/null > "$FIFO" &
  WS_PID=$!

  echo -e "${CYAN}Connecting to EventSub WebSocket...${RESET}"

  local msg_type
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    msg_type=$(echo "$line" | jq -r '.metadata.message_type // empty' 2>/dev/null) || continue
    echo "[raw] type=${msg_type}"
  done < "$FIFO"

  echo "WebSocket closed." >&2
  exit 1
}
```

- [ ] **Step 3: Run shellcheck**

```bash
shellcheck twitch-event-spy.sh
```

Expected: exit 0.

- [ ] **Step 4: Test — verify WebSocket connects and prints raw messages**

```bash
./twitch-event-spy.sh ninja
```

Expected (within ~2 seconds):
```
Resolving channel: ninja
...
Connecting to EventSub WebSocket...
[raw] type=session_welcome
[raw] type=session_keepalive   ← every ~10s
```

Press Ctrl+C → `Disconnected.` message should appear and script should exit cleanly.

---

### Task 4: session_welcome handler and EventSub subscriptions

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1: Add `subscribe_events` function — insert before `main()`**

```bash
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
    twitch api post /helix/eventsub/subscriptions -b "$body" &>/dev/null
    echo -e "  ${CYAN}↳ subscribed:${RESET} ${event_type} v${version}"
  done
}
```

- [ ] **Step 2: Replace the bare event loop inside `main()` with session_welcome dispatch**

Replace the `while` loop body:
```bash
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
        ;;  # silent
      *)
        echo "[unhandled] type=${msg_type}"
        ;;
    esac
  done < "$FIFO"
```

- [ ] **Step 3: Run shellcheck**

```bash
shellcheck twitch-event-spy.sh
```

Expected: exit 0.

- [ ] **Step 4: Test — verify subscriptions register**

```bash
./twitch-event-spy.sh ninja
```

Expected:
```
Connecting to EventSub WebSocket...
Session: <uuid>
  ↳ subscribed: channel.update v2
  ↳ subscribed: stream.online v1
  ↳ subscribed: stream.offline v1
Listening for events on ninja... (Ctrl+C to stop)
```

If the channel is live, trigger a channel update in Twitch dashboard to generate an event. The raw `[unhandled] type=notification` line should appear.

---

### Task 5: Event formatting — notification handler with state tracking and verbose mode

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1: Add `format_event` function — insert before `main()`**

```bash
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
```

- [ ] **Step 2: Replace the `*)` catch-all in the event loop with the notification case**

Replace:
```bash
      *)
        echo "[unhandled] type=${msg_type}"
        ;;
```

With:
```bash
      "notification")
        format_event "$line"
        ;;
      *)
        ;;  # ignore unknown types silently
```

- [ ] **Step 3: Run shellcheck**

```bash
shellcheck twitch-event-spy.sh
```

Expected: exit 0.

- [ ] **Step 4: Test verbose mode compiles cleanly**

```bash
./twitch-event-spy.sh ninja -v
```

Expected: same startup output as before. When a notification arrives it will print the full JSON blob before the formatted line.

---

### Task 6: session_reconnect handling

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1: Add reconnect case to the event loop — inside the `case "$msg_type"` block, after `session_keepalive`**

```bash
      "session_reconnect")
        local new_url
        new_url=$(echo "$line" | jq -r '.payload.session.reconnect_url')
        echo -e "${YELLOW}⚠ Reconnect requested — switching URL...${RESET}"
        kill "$WS_PID" 2>/dev/null || true
        websocat "$new_url" < /dev/null > "$FIFO" &
        WS_PID=$!
        ;;
```

The full `case` block now handles: `session_welcome`, `session_keepalive`, `session_reconnect`, `notification`, `*`.

- [ ] **Step 2: Run shellcheck**

```bash
shellcheck twitch-event-spy.sh
```

Expected: exit 0.

---

### Task 7: Final validation and commit

**Files:**
- No new files

- [ ] **Step 1: Run shellcheck one final time on the complete script**

```bash
shellcheck twitch-event-spy.sh
```

Expected: exit 0, no warnings.

- [ ] **Step 2: Verify the full script content looks correct**

```bash
cat -n twitch-event-spy.sh
```

Confirm the structure matches (top-to-bottom): constants → colors → state globals → `usage` → `check_deps` → `parse_args` → `cleanup` → `get_broadcaster_id` → `fetch_initial_state` → `subscribe_events` → `format_event` → `main`.

- [ ] **Step 3: Manual integration smoke-test**

```bash
./twitch-event-spy.sh <any_live_channel>
```

Checklist:
- Startup prints channel ID and current game/title
- `Listening for events` message appears within 2s
- keepalive messages arrive silently every ~10s (no output)
- Trigger a channel update in Twitch dashboard → game change line appears
- Ctrl+C → `Disconnected.` message, clean exit

- [ ] **Step 4: Commit**

```bash
git add twitch-event-spy.sh
git commit -m "feat: add twitch-event-spy CLI for real-time event logging"
```
