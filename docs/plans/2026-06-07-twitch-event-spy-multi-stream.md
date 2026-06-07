# twitch-event-spy Multi-Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permettre de passer plusieurs channels en arguments positionnels à `twitch-event-spy.sh`, tous surveillés sur une unique connexion WebSocket EventSub.

**Architecture:** Single WebSocket, N souscriptions (une par broadcaster). L'état diff (`PREV_GAME`, `PREV_TITLE`, `PREV_CCLS`) passe de variables globales à des tableaux associatifs indexés par broadcaster ID. Le dispatcher lit le `broadcaster_user_id` dans chaque payload pour router vers le bon état et préfixer l'affichage avec `[login]`.

**Tech Stack:** bash 4+, twitch-cli, websocat, jq

---

## Fichier modifié

- `twitch-event-spy.sh` — seul fichier concerné

---

### Task 1 : Variables d'état et parse_args

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1 : Mettre à jour les déclarations d'état**

Remplacer dans la section `# ── State ──`:

```bash
# ── State ──────────────────────────────────────────────────────────────────
CHANNELS=()
VERBOSE=false
WS_PID=""
declare -A BROADCASTER_IDS=()
declare -A BROADCASTER_NAMES=()
declare -A PREV_GAME=()
declare -A PREV_TITLE=()
declare -A PREV_CCLS=()
```

- [ ] **Step 2 : Mettre à jour usage()**

```bash
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
```

- [ ] **Step 3 : Mettre à jour parse_args()**

```bash
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
```

- [ ] **Step 4 : Valider parse_args**

```bash
bash twitch-event-spy.sh --help
# Attendu : affiche Usage avec "channel [channel2 ...]"

bash twitch-event-spy.sh
# Attendu : "Error: at least one channel name required"

bash twitch-event-spy.sh --unknown
# Attendu : "Unknown option: --unknown"
```

---

### Task 2 : get_broadcaster_id et fetch_initial_state

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1 : Mettre à jour get_broadcaster_id**

La fonction prend désormais le login en `$1` au lieu de lire `$CHANNEL` global :

```bash
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
```

- [ ] **Step 2 : Mettre à jour fetch_initial_state**

La fonction prend `broadcaster_id` en `$1` et indexe l'état par ID :

```bash
fetch_initial_state() {
  local broadcaster_id="$1"
  local response
  response=$(twitch api get /channels -q "broadcaster_id=$broadcaster_id" 2>/dev/null)
  PREV_GAME["$broadcaster_id"]=$(echo "$response" | jq -r '.data[0].game_name // ""')
  PREV_TITLE["$broadcaster_id"]=$(echo "$response" | jq -r '.data[0].title // ""')
  PREV_CCLS["$broadcaster_id"]=$(echo "$response" | jq -r '.data[0].content_classification_labels // [] | sort | join(",")')
}
```

---

### Task 3 : format_event

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1 : Remplacer format_event**

```bash
format_event() {
  local payload="$1"
  local sub_type ts event bid login

  sub_type=$(echo "$payload" | jq -r '.metadata.subscription_type')
  ts=$(date '+%H:%M:%S')
  event=$(echo "$payload" | jq -r '.payload.event')
  bid=$(echo "$payload" | jq -r '.payload.event.broadcaster_user_id')
  login="${BROADCASTER_NAMES[$bid]}"

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
```

- [ ] **Step 2 : Valider le format avec un payload simulé**

```bash
# Sourcer le script sans exécuter main, puis appeler format_event avec un payload mock
bash -c '
  source twitch-event-spy.sh 2>/dev/null || true
  BROADCASTER_NAMES["123456"]="ninja"
  PREV_GAME["123456"]="Fortnite"
  PREV_TITLE["123456"]="old title"
  PREV_CCLS["123456"]=""
  payload='"'"'{"metadata":{"subscription_type":"channel.update"},"payload":{"event":{"broadcaster_user_id":"123456","category_name":"Minecraft","title":"new title","content_classification_labels":[]}}}'"'"'
  format_event "$payload"
' 2>/dev/null
# Attendu :
# [HH:MM:SS] [ninja] 🎮 GAME CHANGE     Fortnite → Minecraft
# [HH:MM:SS] [ninja]    TITLE CHANGE    "old title" → "new title"
```

Note : sourcer le script échouera sur `main "$@"` à la fin — c'est attendu, seule la sortie de `format_event` est vérifiée.

---

### Task 4 : main

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1 : Remplacer la section d'initialisation dans main**

Remplacer les lignes qui résolvent un seul channel par une boucle :

```bash
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
        channels_str=$(IFS=,; echo "${CHANNELS[*]}")
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
```

- [ ] **Step 2 : Vérifier la syntaxe bash**

```bash
bash -n twitch-event-spy.sh
# Attendu : aucune sortie (pas d'erreur de syntaxe)
```

---

### Task 5 : Commit

**Files:**
- Modify: `twitch-event-spy.sh`

- [ ] **Step 1 : Vérifier l'état git**

```bash
git diff twitch-event-spy.sh
```

- [ ] **Step 2 : Commit**

```bash
git add twitch-event-spy.sh
git commit -m "feat(twitch-event-spy): support multiple streams as positional arguments"
```
