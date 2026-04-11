# Live Check via Twitch EventSub WebSocket

**Date:** 2026-04-12  
**Branch:** feat/merge-dashboard-bindings-settings

## Contexte

Actuellement, `SchedulerService` met à jour la catégorie Twitch et les CCLs dès qu'un changement de jeu est détecté — sans vérifier si l'utilisateur est en live. Cette feature ajoute une vérification de statut live via Twitch EventSub WebSocket, stocke les mises à jour en attente si le stream n'est pas actif, les applique dès que le live démarre, et remet la catégorie par défaut à la fin du stream.

## Architecture

### Nouveaux composants

| Composant | Rôle |
|-----------|------|
| `StreamStateService` | Stocke l'état live + binding en attente par utilisateur (ConcurrentHashMap, comme GameStateService) |
| `TwitchEventSubService` | Gère une connexion WebSocket EventSub par user actif, publie des événements Spring |
| `StreamOnlineEvent` | Événement Spring publié à réception de `stream.online` |
| `StreamOfflineEvent` | Événement Spring publié à réception de `stream.offline` |

### Composants modifiés

| Composant | Modification |
|-----------|-------------|
| `GameEventListener` | Vérifie `StreamStateService.isLive()` avant d'appeler `TwitchService.updateChannel()` ; écoute `StreamOnlineEvent` / `StreamOfflineEvent` |
| `TwitchService` | Nouvelle méthode `resetToDefault(user)` qui envoie un PATCH avec `noGameTwitchGameId` |
| `status.html` | Indicateur live/offline basé sur `StreamStateService.isLive(user)` |

## Flux de données

### Changement de jeu détecté

```
SchedulerService.poll()
  → GameDetectedEvent
    → GameEventListener.onGameDetected()
        → binding = bindingService.resolve(user, game)
        → StreamStateService.isLive(user) ?
            OUI → TwitchService.updateChannel(user, binding)
            NON → StreamStateService.storePending(user, binding)
```

### Stream démarre

```
TwitchEventSubService (WebSocket)
  → notification stream.online
    → StreamStateService.setLive(user, true)
    → StreamOnlineEvent publié
      → GameEventListener.onStreamOnline()
          → pending = StreamStateService.getPending(user)
          → pending != null → TwitchService.updateChannel(user, pending)
          → StreamStateService.clearPending(user)
```

### Stream se termine

```
TwitchEventSubService (WebSocket)
  → notification stream.offline
    → StreamStateService.setLive(user, false)
    → StreamOfflineEvent publié
      → GameEventListener.onStreamOffline()
          → TwitchService.resetToDefault(user)   // PATCH noGameTwitchGameId si configuré
          → StreamStateService.clearPending(user)
          → GameStateService.clearState(user)
```

## `StreamStateService`

```java
// État en mémoire
Map<UUID, Boolean> liveStatus         // isLive par user
Map<UUID, GameBinding> pendingBinding // binding en attente par user

// API
boolean isLive(UserAccount user)
void setLive(UserAccount user, boolean live)
void storePending(UserAccount user, GameBinding binding)
Optional<GameBinding> getPending(UserAccount user)
void clearPending(UserAccount user)
```

Initialisé avec `isLive=false` pour tous les users au démarrage. Le premier event EventSub corrige l'état réel.

## `TwitchEventSubService`

### Cycle de vie

- `@PostConstruct` : pour chaque user `botEnabled=true` avec token Twitch valide → `connect(user)`
- `connect(user)` : ouvre un `WebSocketClient` Java vers `wss://eventsub.wss.twitch.tv/ws`
- À `session_welcome` → POST `/helix/eventsub/subscriptions` pour `stream.online` et `stream.offline` du broadcaster
- `disconnect(user)` : ferme la connexion, vide l'état live et pending
- Méthodes `connect(user)` / `disconnect(user)` publiques → appelées par le contrôleur bot settings

### Reconnexion

| Cas | Comportement |
|-----|-------------|
| `session_reconnect` reçu | Ouvre une nouvelle connexion vers l'URL fournie, ferme l'ancienne |
| Déconnexion inattendue | Retry avec backoff exponentiel : 1s, 2s, 4s, 8s… max 60s |
| Token expiré (401 sur POST subscription) | Log warn, pas de crash — le bot continue sans vérification live |

## `TwitchService.resetToDefault(user)`

- Lit `UserSettings.noGameTwitchGameId` pour l'utilisateur
- Si non configuré → log debug, retour immédiat (aucun appel API)
- Si configuré → PATCH `/helix/channels` avec `game_id = noGameTwitchGameId`
- Même gestion d'erreurs que `updateChannel()` (401 → désactiver bot, 429 → ignorer)

## UI — `status.html`

Ajout d'un indicateur dans la carte de statut :

```html
<!-- Live status -->
<div th:if="${isLive}" class="status status-live">
    <span class="status-dot"></span> En live
</div>
<div th:unless="${isLive}" class="status status-offline">
    <span class="status-dot"></span> Hors ligne
</div>
```

`isLive` est fourni par `AppController` via `StreamStateService.isLive(user)`. Le fragment est déjà rafraîchi par HTMX toutes les 3s.

## Gestion d'erreurs & cas limites

| Cas | Comportement |
|-----|-------------|
| User désactive le bot | `disconnect(user)` → clear pending + clear live state |
| User réactive le bot | `connect(user)` |
| `noGameTwitchGameId` absent au `stream.offline` | Log debug, pas d'appel PATCH |
| Démarrage application | `isLive=false` pour tous — aucun appel API au boot |
| Thread-safety | `ConcurrentHashMap` dans `StreamStateService`, events via `ApplicationEventPublisher` |

## Fichiers à créer / modifier

**Créer :**
- `service/StreamStateService.java`
- `service/TwitchEventSubService.java`
- `event/StreamOnlineEvent.java`
- `event/StreamOfflineEvent.java`

**Modifier :**
- `event/GameEventListener.java` — vérification live + handlers online/offline
- `service/TwitchService.java` — méthode `resetToDefault()`
- `web/AppController.java` — exposer `isLive` au modèle Thymeleaf
- `templates/fragments/status.html` — indicateur live/offline
