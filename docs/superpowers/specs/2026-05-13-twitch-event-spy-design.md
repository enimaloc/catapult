# Twitch Event Spy — Design Spec

**Date:** 2026-05-13
**Status:** Approved
**Purpose:** Dev/debug CLI utility to log Twitch channel events in real-time (game changes, stream online/offline)

---

## Context

Catapult already integrates Twitch EventSub via WebSocket in Java. During development, there is no easy way to observe raw events from a channel without running the full application. This tool fills that gap.

---

## Architecture

```
twitch-event-spy.sh <channel>
        │
        ├─ twitch token          → retrieves Bearer token (managed by twitch-cli)
        ├─ twitch api get users  → resolves login name → broadcaster_id
        │
        ├─ mkfifo /tmp/twitch_ws_fifo_$$
        ├─ websocat wss://eventsub.wss.twitch.tv/ws > fifo &  (background process)
        │
        └─ while read line from fifo:
              session_welcome  → POST eventsub/subscriptions for each entry in SUBSCRIPTIONS[]
              notification     → parse with jq + display formatted event
              keepalive        → ignored
              [SIGINT]         → cleanup fifo + kill websocat PID
```

### Dependencies

| Tool | Role |
|------|------|
| `twitch-cli` | Auth token management + Helix API calls |
| `websocat` | EventSub WebSocket client |
| `jq` | JSON parsing and formatting |

All three must be installed and available in `$PATH`. The script checks for them at startup and exits with a clear error if any is missing.

`twitch-cli` must be pre-configured via `twitch configure` (Client ID + Secret stored by twitch-cli itself).

---

## CLI Interface

```
Usage: twitch-event-spy.sh <channel> [-v] [--help]

Arguments:
  <channel>    Twitch channel login name (e.g. "ninja")

Options:
  -v           Verbose mode — also prints raw JSON for each event
  --help       Show usage and exit
```

No auth arguments: credentials are managed entirely by `twitch-cli`.

---

## Event Subscriptions

Defined as an associative array at the top of the script so adding a new event requires only one new entry:

```bash
declare -A SUBSCRIPTIONS=(
  ["channel.update"]="2"
  ["stream.online"]="1"
  ["stream.offline"]="1"
)
```

Each subscription is registered via `twitch api post /helix/eventsub/subscriptions` immediately after receiving the `session_welcome` message and extracting the `session_id`.

---

## Output Format

Each event prints a timestamped line to stdout:

```
[14:32:01] 🟢 STREAM ONLINE   ninja
[14:35:10] 🎮 GAME CHANGE     Fortnite → Valorant
[14:35:10]    TITLE CHANGE    "!code" → "Ranked grind"
[14:55:00] 🔴 STREAM OFFLINE  ninja
```

Rules:
- `channel.update` may change game and/or title simultaneously — each changed field gets its own line, same timestamp.
- If neither game nor title changed (empty diff), no line is printed.
- In `-v` mode, the raw JSON payload is printed below the formatted line(s), indented with `jq`.

---

## Lifecycle & Cleanup

- A `trap cleanup SIGINT SIGTERM EXIT` ensures the FIFO and background `websocat` process are always removed on exit.
- The FIFO path is `/tmp/twitch_ws_fifo_$$` (PID-scoped to avoid collisions when running multiple instances).
- If `websocat` exits unexpectedly (e.g. network drop), the read loop ends and the script exits with a non-zero code and a clear message.

---

## File Location

```
twitch-event-spy.sh
```

Placed at the repo root (no `scripts/` directory exists in this project).

---

## Out of Scope

- Persistent logging to file (redirect stdout for that: `./twitch-event-spy.sh ninja >> events.log`)
- Auto-reconnect on disconnect (dev tool, not production daemon)
- Filtering by event type at runtime (add/remove entries in `SUBSCRIPTIONS[]` instead)
