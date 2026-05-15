# Design — Service Twitch Chat

**Date :** 2026-05-16
**Statut :** validé

---

## Contexte

Le projet dispose déjà d'une infrastructure de commandes chat complète (`ChatCommand`, `CommandRegistry`, `ChatCommandEvent`, `ChatCommandListener`) mais il manque le connecteur qui lit le chat Twitch réel et publie ces événements. Ce document décrit le service qui comble ce manque.

---

## Objectifs

- Connecter l'application au chat Twitch (réception des messages)
- Répondre dans le chat (envoi de messages)
- Gérer la modération : timeout, ban, unban
- Recevoir les channel point rewards
- Rester configurable via `application.properties` (provider sélectionnable)
- Trois implémentations : IRC, EventSub, Mock

---

## Interface `TwitchChatService`

```java
public interface TwitchChatService {
    void connect(UserAccount user);
    void disconnect(UserAccount user);
    void sendMessage(UserAccount user, String message);
    void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason);
    void ban(UserAccount user, String targetLogin, String reason);
    void unban(UserAccount user, String targetLogin);
}
```

La réception des messages est gérée en interne par chaque implémentation, qui publie des `ChatCommandEvent` via `ApplicationEventPublisher`.

---

## Changements sur `ChatCommand`

`execute()` retourne désormais `Object` au lieu de `void` :

```java
public interface ChatCommand {
    String getName();
    ChatCommandEvent.SenderRole getRequiredPermission();
    Object execute(UserAccount user, List<String> args);
}
```

- `null` → pas de réponse dans le chat
- `String` → envoyé tel quel via `sendMessage`
- Autre type → sérialisé en JSON (`ObjectMapper`) ou `toString()` en fallback

`CommandRegistry` gère la sérialisation et appelle `twitchChatService.sendMessage()`. Il reçoit `TwitchChatService` par injection.

---

## Configuration

```properties
# ============================================================
# Chat Twitch (irc | eventsub | mock)
# ============================================================
app.chat.provider=mock
```

Chaque implémentation est annotée `@ConditionalOnProperty(name="app.chat.provider", havingValue="...")`. Un seul bean est instancié au démarrage.

Les scopes OAuth2 ajoutés à la configuration Twitch existante :
- `user:read:chat`
- `user:write:chat`
- `channel:moderate`
- `channel:read:redemptions` (pour les rewards EventSub)

---

## Implémentations

### `IrcTwitchChatService` (`app.chat.provider=irc`)

- Connexion TLS à `irc.chat.twitch.tv:6697`
- Authentification : `PASS oauth:<token>` + `NICK <login>` + `CAP REQ :twitch.tv/tags twitch.tv/commands` + `JOIN #<channel>`
- Lecture sur thread dédié (`ExecutorService`) : parse les `PRIVMSG`, lit les tags IRC pour extraire le rôle sender (`@badges=broadcaster/...` → `BROADCASTER`, `moderator/...` → `MODERATOR`, sinon `EVERYONE`)
- Détection de commande : message débutant par `!`
- Publication de `ChatCommandEvent` via `ApplicationEventPublisher`
- Envoi via `PRIVMSG #<channel> :<message>`
- Timeout/ban via Helix API REST (`POST /helix/moderation/bans`)
- Channel point rewards : non supportés (pas de subscription EventSub depuis IRC) — `connect()` loggue un avertissement si des rewards sont configurés

### `EventSubTwitchChatService` (`app.chat.provider=eventsub`)

- Réutilise le pattern WebSocket de `TwitchEventSubService` (même `HttpClient`, même listener, même backoff)
- Subscriptions EventSub : `channel.chat.message`, `channel.channel_points_custom_reward_redemption.add`
- Extraction du rôle depuis le payload JSON (`chatter_type`)
- Rewards : publie `ChatCommandEvent` avec commande synthétique `reward:<reward-title>` et rôle `EVERYONE`
- Envoi de messages via `POST /helix/chat/messages`
- Timeout/ban via `POST /helix/moderation/bans`

### `MockTwitchChatService` (`app.chat.provider=mock`)

- `connect`/`disconnect` : log info, no-op
- `sendMessage` : log info avec le message
- `timeout`/`ban`/`unban` : log info
- Méthode utilitaire `simulateMessage(UserAccount, String senderLogin, SenderRole, String text)` pour les tests
- Méthode utilitaire `simulateReward(UserAccount, String rewardTitle)` pour les tests

---

## Data flow

```
Chat Twitch
    │
    ▼
TwitchChatService (IRC thread / EventSub WebSocket)
  ├─ parse message → commande, args, senderRole
  └─ publie ChatCommandEvent(user, command, args, senderRole)
    │
    ▼
ChatCommandListener.onChatCommand()   [@EventListener]
    │
    ▼
CommandRegistry.dispatch()
  ├─ lookup commande
  ├─ hasPermission() 
  └─ result = command.execute(user, args)
    │
    ▼
CommandRegistry (sérialisation)
  ├─ result == null → pas de réponse
  ├─ result instanceof String → sendMessage directement
  └─ sinon → objectMapper.writeValueAsString(result) ou toString()
    │
    ▼
twitchChatService.sendMessage(user, message)
    │
    ▼
Chat Twitch ← réponse
```

---

## Gestion des erreurs & reconnexion

- **IRC** : backoff exponentiel sur perte de connexion (1s → 2s → … → 60s max), identique à `TwitchEventSubService`
- **EventSub** : même stratégie WebSocket existante (`session_reconnect`, `onError`, `onClose`)
- **Token expiré** : refresh via `OAuthTokenRepository` avant reconnexion
- **`sendMessage` échoue** : log warn + échec silencieux (la commande a été exécutée, seule la réponse est perdue)
- **Exception dans `execute()`** : déjà capturée par `CommandRegistry.dispatch()` — pas de changement

---

## Fichiers à créer / modifier

| Action | Fichier |
|--------|---------|
| Créer | `service/TwitchChatService.java` (interface) |
| Créer | `service/IrcTwitchChatService.java` |
| Créer | `service/EventSubTwitchChatService.java` |
| Créer | `service/MockTwitchChatService.java` |
| Modifier | `chat/ChatCommand.java` (`void` → `Object`) |
| Modifier | `chat/CommandRegistry.java` (injection `TwitchChatService`, sérialisation) |
| Modifier | `chat/command/GameCommand.java` (retourner `String`) |
| Modifier | `chat/command/SetGameCommand.java` (retourner `String` ou `null`) |
| Modifier | `config/OAuth2ClientConfig.java` (nouveaux scopes) |
| Modifier | `application.properties` (propriété `app.chat.provider`) |
