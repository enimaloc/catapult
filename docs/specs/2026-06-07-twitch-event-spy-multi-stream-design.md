# twitch-event-spy : support multi-stream

Date: 2026-06-07

## Objectif

Permettre de surveiller plusieurs channels Twitch simultanément dans une seule invocation de `twitch-event-spy.sh`, via une unique connexion WebSocket EventSub.

## Syntaxe

```
./twitch-event-spy.sh <channel1> [channel2 ...] [-v] [--help]
```

Exemples :
```
./twitch-event-spy.sh ninja
./twitch-event-spy.sh ninja pokimane xqc -v
```

## Architecture

### Approche retenue : single WebSocket, multiple souscriptions

Twitch EventSub WebSocket supporte jusqu'à 300 souscriptions par session. Tous les channels sont souscrits sur la même connexion — pas de processus multiples ni de FIFOs supplémentaires.

### Changements d'état

| Avant | Après |
|---|---|
| `CHANNEL=""` | `CHANNELS=()` |
| `PREV_GAME=""` | `declare -A PREV_GAME=()` |
| `PREV_TITLE=""` | `declare -A PREV_TITLE=()` |
| `PREV_CCLS=""` | `declare -A PREV_CCLS=()` |

Nouveaux tableaux associatifs :
- `BROADCASTER_IDS[login]=broadcaster_id` — résolution au démarrage
- `BROADCASTER_NAMES[broadcaster_id]=login` — lookup pour l'affichage

### Flux d'initialisation

1. `parse_args` accumule les arguments positionnels dans `CHANNELS[]`
2. Pour chaque channel, `get_broadcaster_id <login>` et `fetch_initial_state <broadcaster_id>` sont appelés — l'état diff est stocké dans `PREV_GAME[$id]`, `PREV_TITLE[$id]`, `PREV_CCLS[$id]`
3. Sur `session_welcome`, `subscribe_events` est appelé une fois par broadcaster ID

### Fonctions modifiées

**`get_broadcaster_id(login)`** — reçoit le login en paramètre, ne lit plus `$CHANNEL` global.

**`fetch_initial_state(broadcaster_id)`** — indexe l'état dans les tableaux associatifs par broadcaster ID.

**`subscribe_events(session_id, broadcaster_id)`** — inchangée dans sa signature, appelée N fois.

**`format_event(payload)`** — extrait `broadcaster_user_id` du payload pour :
- retrouver le login via `BROADCASTER_NAMES[$bid]`
- lire/écrire l'état diff via `PREV_GAME[$bid]` etc.
- préfixer chaque ligne avec `[login]`

**`main()`** — itère sur `CHANNELS[]` pour la résolution, et sur `BROADCASTER_IDS[@]` pour les souscriptions post-welcome.

### Format d'affichage

Chaque ligne d'événement inclut le login entre crochets après le timestamp :

```
[12:34:56] [ninja]    🟢 STREAM ONLINE
[12:35:10] [pokimane] 🎮 GAME CHANGE     Fortnite → Minecraft
[12:36:02] [ninja]       TITLE CHANGE    "old title" → "new title"
```

### Reconnexion

La logique `session_reconnect` reste inchangée : une seule connexion WS est gérée, et les souscriptions sont automatiquement portées par la nouvelle session via re-souscription au `session_welcome` suivant.

## Ce qui ne change pas

- Dépendances (`twitch`, `websocat`, `jq`)
- Flag `-v` (verbose, affiche le JSON brut pour chaque event)
- Gestion du FIFO et du cleanup (`trap`)
- Structure des souscriptions (`SUBSCRIPTIONS` map)
- Logique `session_keepalive` et `session_reconnect`
