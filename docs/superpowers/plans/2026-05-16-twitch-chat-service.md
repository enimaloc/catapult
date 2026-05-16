# Twitch Chat Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connecter l'application au chat Twitch en lecture + écriture + modération via une interface configurable avec trois implémentations (Mock, IRC, EventSub).

**Architecture:** Interface `TwitchChatService` sélectionnée par `@ConditionalOnProperty(app.chat.provider)`. La réception des messages publie des `ChatCommandEvent` via `ApplicationEventPublisher` ; `CommandRegistry` sérialise le résultat de `execute()` et appelle `sendMessage`. Chaque implémentation gère sa propre connexion avec reconnexion exponentielle.

**Tech Stack:** Spring Boot 4, Java 17, `javax.net.ssl.SSLSocket` (IRC), `java.net.http.WebSocket` (EventSub), Twitch Helix REST API, Lombok, JUnit 5 + AssertJ (tests).

---

## Fichiers

| Action | Chemin complet |
|--------|----------------|
| Créer | `src/main/java/fr/enimaloc/catapult/service/TwitchChatService.java` |
| Créer | `src/main/java/fr/enimaloc/catapult/service/MockTwitchChatService.java` |
| Créer | `src/main/java/fr/enimaloc/catapult/service/IrcTwitchChatService.java` |
| Créer | `src/main/java/fr/enimaloc/catapult/service/EventSubTwitchChatService.java` |
| Modifier | `src/main/java/fr/enimaloc/catapult/chat/ChatCommand.java` |
| Modifier | `src/main/java/fr/enimaloc/catapult/chat/CommandRegistry.java` |
| Modifier | `src/main/java/fr/enimaloc/catapult/chat/command/GameCommand.java` |
| Modifier | `src/main/java/fr/enimaloc/catapult/chat/command/SetGameCommand.java` |
| Modifier | `src/main/java/fr/enimaloc/catapult/config/OAuth2ClientConfig.java` |
| Modifier | `src/main/resources/application.properties` |
| Créer | `src/test/java/fr/enimaloc/catapult/service/IrcTwitchChatServiceTest.java` |
| Créer | `src/test/java/fr/enimaloc/catapult/chat/CommandRegistryTest.java` |

---

## Task 1 : Interface `TwitchChatService` + propriété de configuration

**Files:**
- Créer: `src/main/java/fr/enimaloc/catapult/service/TwitchChatService.java`
- Modifier: `src/main/resources/application.properties`

- [ ] **Créer l'interface**

```java
// src/main/java/fr/enimaloc/catapult/service/TwitchChatService.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;

public interface TwitchChatService {
    void connect(UserAccount user);
    void disconnect(UserAccount user);
    void sendMessage(UserAccount user, String message);
    void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason);
    void ban(UserAccount user, String targetLogin, String reason);
    void unban(UserAccount user, String targetLogin);
}
```

- [ ] **Ajouter la propriété dans `application.properties`** (après la section Battle.net)

```properties
# ============================================================
# Chat Twitch (irc | eventsub | mock — défaut : mock)
# ============================================================
app.chat.provider=mock
```

- [ ] **Compiler**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/TwitchChatService.java \
        src/main/resources/application.properties
git commit -m "feat: add TwitchChatService interface and chat provider config"
```

---

## Task 2 : `MockTwitchChatService`

**Files:**
- Créer: `src/main/java/fr/enimaloc/catapult/service/MockTwitchChatService.java`

- [ ] **Créer le service mock**

```java
// src/main/java/fr/enimaloc/catapult/service/MockTwitchChatService.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "mock", matchIfMissing = true)
@RequiredArgsConstructor
public class MockTwitchChatService implements TwitchChatService {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void connect(UserAccount user) {
        log.info("[Mock Chat] connect() for user {}", user.getId());
    }

    @Override
    public void disconnect(UserAccount user) {
        log.info("[Mock Chat] disconnect() for user {}", user.getId());
    }

    @Override
    public void sendMessage(UserAccount user, String message) {
        log.info("[Mock Chat] sendMessage() for {}: {}", user.getTwitchUsername(), message);
    }

    @Override
    public void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        log.info("[Mock Chat] timeout() for {}: {} for {}s (reason: {})",
            user.getId(), targetLogin, durationSeconds, reason);
    }

    @Override
    public void ban(UserAccount user, String targetLogin, String reason) {
        log.info("[Mock Chat] ban() for {}: {} (reason: {})", user.getId(), targetLogin, reason);
    }

    @Override
    public void unban(UserAccount user, String targetLogin) {
        log.info("[Mock Chat] unban() for {}: {}", user.getId(), targetLogin);
    }

    public void simulateMessage(UserAccount user, String senderLogin,
                                ChatCommandEvent.SenderRole role, String text) {
        if (!text.startsWith("!")) return;
        String[] parts = text.substring(1).split(" ", 2);
        String command = "!" + parts[0];
        List<String> args = parts.length > 1 ? List.of(parts[1].split(" ")) : List.of();
        eventPublisher.publishEvent(new ChatCommandEvent(this, user, command, args, role));
        log.info("[Mock Chat] simulateMessage() for {}: '{}' from {} ({})",
            user.getId(), text, senderLogin, role);
    }

    public void simulateReward(UserAccount user, String rewardTitle) {
        String command = "reward:" + rewardTitle;
        eventPublisher.publishEvent(new ChatCommandEvent(this, user, command, List.of(),
            ChatCommandEvent.SenderRole.EVERYONE));
        log.info("[Mock Chat] simulateReward() for {}: '{}'", user.getId(), rewardTitle);
    }
}
```

- [ ] **Compiler**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/MockTwitchChatService.java
git commit -m "feat: add MockTwitchChatService with simulate helpers"
```

---

## Task 3 : Mettre à jour `ChatCommand`, `GameCommand`, `SetGameCommand`

> Ces trois fichiers doivent être modifiés dans un même commit : `execute()` passe de `void` à `Object`, ce qui casse la compilation jusqu'à ce que toutes les implémentations soient mises à jour.

**Files:**
- Modifier: `src/main/java/fr/enimaloc/catapult/chat/ChatCommand.java`
- Modifier: `src/main/java/fr/enimaloc/catapult/chat/command/GameCommand.java`
- Modifier: `src/main/java/fr/enimaloc/catapult/chat/command/SetGameCommand.java`

- [ ] **Mettre à jour `ChatCommand.java`** (remplacer `void execute` par `Object execute`)

```java
// src/main/java/fr/enimaloc/catapult/chat/ChatCommand.java
package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.UserAccount;

import java.util.List;

/**
 * Interface d'une commande chat Twitch.
 * L'ajout d'une nouvelle commande ne nécessite aucune modification de TwitchChatListener.
 */
public interface ChatCommand {

    String getName();

    ChatCommandEvent.SenderRole getRequiredPermission();

    Object execute(UserAccount user, List<String> args);
}
```

- [ ] **Mettre à jour `GameCommand.java`**

Remplacer la méthode `execute` par :

```java
@Override
public Object execute(UserAccount user, List<String> args) {
    return gameStateService.getLastKnownGame(user)
        .map(game -> "Actuellement : " + game.getSourceName())
        .orElse("Aucun jeu détecté");
}
```

Supprimer l'import `lombok.extern.slf4j.Slf4j` et l'annotation `@Slf4j` si `log` n'est plus utilisé.

- [ ] **Mettre à jour `SetGameCommand.java`**

Remplacer la méthode `execute` par :

```java
@Override
public Object execute(UserAccount user, List<String> args) {
    if (args.isEmpty()) {
        return "Usage : !setgame <nom du jeu>";
    }

    String gameName = String.join(" ", args);
    log.info("[!setgame] User {} requested manual game: {}", user.getTwitchUsername(), gameName);

    GameBinding tempBinding = new GameBinding();
    tempBinding.setUser(user);
    tempBinding.setSourceType(GameBinding.SourceType.MANUAL);
    tempBinding.setSourceName(gameName);
    tempBinding.setTwitchGameName(gameName);
    tempBinding.setStatus(GameBinding.Status.MANUAL);
    tempBinding.setCcls(Set.of());

    twitchService.updateChannel(user, tempBinding);
    return "Jeu mis à jour : " + gameName;
}
```

- [ ] **Compiler**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/chat/ChatCommand.java \
        src/main/java/fr/enimaloc/catapult/chat/command/GameCommand.java \
        src/main/java/fr/enimaloc/catapult/chat/command/SetGameCommand.java
git commit -m "feat: change ChatCommand.execute() return type to Object for chat responses"
```

---

## Task 4 : Mettre à jour `CommandRegistry`

**Files:**
- Modifier: `src/main/java/fr/enimaloc/catapult/chat/CommandRegistry.java`
- Créer: `src/test/java/fr/enimaloc/catapult/chat/CommandRegistryTest.java`

- [ ] **Écrire le test qui vérifie que la réponse d'une commande est envoyée via `sendMessage`**

```java
// src/test/java/fr/enimaloc/catapult/chat/CommandRegistryTest.java
package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class CommandRegistryTest {

    @Test
    void dispatchSendsStringResponseToChat() {
        UserAccount user = new UserAccount();
        TwitchChatService chatService = mock(TwitchChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ChatCommand command = new ChatCommand() {
            @Override public String getName() { return "!test"; }
            @Override public ChatCommandEvent.SenderRole getRequiredPermission() { return ChatCommandEvent.SenderRole.EVERYONE; }
            @Override public Object execute(UserAccount u, List<String> args) { return "réponse"; }
        };

        CommandRegistry registry = new CommandRegistry(List.of(command), chatService, objectMapper);
        ChatCommandEvent event = new ChatCommandEvent(this, user, "!test", List.of(), ChatCommandEvent.SenderRole.EVERYONE);

        registry.dispatch(event);

        verify(chatService).sendMessage(user, "réponse");
    }

    @Test
    void dispatchSkipsSendWhenResultIsNull() {
        UserAccount user = new UserAccount();
        TwitchChatService chatService = mock(TwitchChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ChatCommand command = new ChatCommand() {
            @Override public String getName() { return "!silent"; }
            @Override public ChatCommandEvent.SenderRole getRequiredPermission() { return ChatCommandEvent.SenderRole.EVERYONE; }
            @Override public Object execute(UserAccount u, List<String> args) { return null; }
        };

        CommandRegistry registry = new CommandRegistry(List.of(command), chatService, objectMapper);
        ChatCommandEvent event = new ChatCommandEvent(this, user, "!silent", List.of(), ChatCommandEvent.SenderRole.EVERYONE);

        registry.dispatch(event);

        verify(chatService, never()).sendMessage(any(), any());
    }

    @Test
    void dispatchSerializesObjectResultToJson() {
        UserAccount user = new UserAccount();
        TwitchChatService chatService = mock(TwitchChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        record Info(String name) {}
        ChatCommand command = new ChatCommand() {
            @Override public String getName() { return "!info"; }
            @Override public ChatCommandEvent.SenderRole getRequiredPermission() { return ChatCommandEvent.SenderRole.EVERYONE; }
            @Override public Object execute(UserAccount u, List<String> args) { return new Info("Minecraft"); }
        };

        CommandRegistry registry = new CommandRegistry(List.of(command), chatService, objectMapper);
        ChatCommandEvent event = new ChatCommandEvent(this, user, "!info", List.of(), ChatCommandEvent.SenderRole.EVERYONE);

        registry.dispatch(event);

        verify(chatService).sendMessage(user, "{\"name\":\"Minecraft\"}");
    }
}
```

- [ ] **Lancer le test pour vérifier qu'il échoue**

```bash
./gradlew test --tests "fr.enimaloc.catapult.chat.CommandRegistryTest" 2>&1 | tail -20
```

Expected: `CommandRegistry` ne compile pas encore car le constructeur ne prend pas encore `TwitchChatService` et `ObjectMapper`.

- [ ] **Réécrire `CommandRegistry.java`**

```java
// src/main/java/fr/enimaloc/catapult/chat/CommandRegistry.java
package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.service.TwitchChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registre centralisé des commandes chat disponibles.
 * Toutes les implémentations de ChatCommand sont auto-découvertes par Spring.
 */
@Slf4j
@Component
public class CommandRegistry {

    private final Map<String, ChatCommand> commands;
    private final TwitchChatService twitchChatService;
    private final ObjectMapper objectMapper;

    public CommandRegistry(List<ChatCommand> commandList,
                           TwitchChatService twitchChatService,
                           ObjectMapper objectMapper) {
        this.commands = commandList.stream()
            .collect(Collectors.toMap(ChatCommand::getName, Function.identity()));
        this.twitchChatService = twitchChatService;
        this.objectMapper = objectMapper;
        log.info("Registered {} chat commands: {}", commands.size(), commands.keySet());
    }

    public void dispatch(ChatCommandEvent event) {
        ChatCommand command = commands.get(event.getCommand());
        if (command == null) {
            log.debug("Unknown command '{}' for user {}", event.getCommand(), event.getUser().getId());
            return;
        }

        if (!hasPermission(event.getSenderRole(), command.getRequiredPermission())) {
            log.debug("Permission denied for command '{}' — sender role: {}",
                event.getCommand(), event.getSenderRole());
            return;
        }

        try {
            Object result = command.execute(event.getUser(), event.getArgs());
            if (result != null) {
                twitchChatService.sendMessage(event.getUser(), serialize(result));
            }
        } catch (Exception e) {
            log.error("Error executing command '{}' for user {}",
                event.getCommand(), event.getUser().getId(), e);
        }
    }

    private String serialize(Object result) {
        if (result instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return result.toString();
        }
    }

    private boolean hasPermission(ChatCommandEvent.SenderRole senderRole,
                                  ChatCommandEvent.SenderRole required) {
        return switch (required) {
            case EVERYONE -> true;
            case MODERATOR -> senderRole == ChatCommandEvent.SenderRole.MODERATOR
                || senderRole == ChatCommandEvent.SenderRole.BROADCASTER;
            case BROADCASTER -> senderRole == ChatCommandEvent.SenderRole.BROADCASTER;
        };
    }
}
```

- [ ] **Lancer les tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.chat.CommandRegistryTest"
```

Expected: `3 tests completed, 0 failed`

- [ ] **Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/chat/CommandRegistry.java \
        src/test/java/fr/enimaloc/catapult/chat/CommandRegistryTest.java
git commit -m "feat: wire TwitchChatService into CommandRegistry for chat responses"
```

---

## Task 5 : Scopes OAuth2 Twitch

**Files:**
- Modifier: `src/main/java/fr/enimaloc/catapult/config/OAuth2ClientConfig.java`

- [ ] **Ajouter les scopes** dans la section `registrations.add(ClientRegistration.withRegistrationId("twitch")...`

Remplacer la ligne `.scope(...)` par :

```java
.scope("user:read:email", "channel:manage:broadcast",
       "user:read:chat", "user:write:chat",
       "channel:moderate", "channel:read:redemptions")
```

- [ ] **Compiler**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/config/OAuth2ClientConfig.java
git commit -m "feat: add Twitch chat OAuth2 scopes (user:read:chat, user:write:chat, channel:moderate, channel:read:redemptions)"
```

---

## Task 6 : `IrcTwitchChatService`

**Files:**
- Créer: `src/main/java/fr/enimaloc/catapult/service/IrcTwitchChatService.java`
- Créer: `src/test/java/fr/enimaloc/catapult/service/IrcTwitchChatServiceTest.java`

- [ ] **Écrire les tests unitaires pour `extractRole` et `handleLine`**

```java
// src/test/java/fr/enimaloc/catapult/service/IrcTwitchChatServiceTest.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IrcTwitchChatServiceTest {

    @Test
    void extractRoleBroadcaster() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=subscriber/12;badges=broadcaster/1,subscriber/0;color=#FF0000");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void extractRoleModerator() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=;badges=moderator/1;color=#00FF00");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.MODERATOR);
    }

    @Test
    void extractRoleEveryoneWhenSubscriberOnly() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=subscriber/3;badges=subscriber/3;color=");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.EVERYONE);
    }

    @Test
    void extractRoleEveryoneWhenEmptyTags() {
        var role = IrcTwitchChatService.extractRole("");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.EVERYONE);
    }

    @Test
    void handleLinePongOnPing() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        IrcTwitchChatService service = buildService(publisher);
        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        service.handleLine(user, writer, "PING :tmi.twitch.tv");

        assertThat(sw.toString().trim()).isEqualTo("PONG :tmi.twitch.tv");
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void handleLinePublishesChatCommandEvent() {
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;
        IrcTwitchChatService service = buildService(publisher);

        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        String line = "@badges=broadcaster/1;color=#FF0000 :streamer!streamer@streamer.tmi.twitch.tv PRIVMSG #streamer :!game";
        service.handleLine(user, writer, line);

        assertThat(published).hasSize(1);
        ChatCommandEvent event = (ChatCommandEvent) published.get(0);
        assertThat(event.getCommand()).isEqualTo("!game");
        assertThat(event.getArgs()).isEmpty();
        assertThat(event.getSenderRole()).isEqualTo(ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void handleLineIgnoresNonCommandMessages() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        IrcTwitchChatService service = buildService(publisher);

        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        service.handleLine(user, writer,
            "@badges= :viewer!viewer@viewer.tmi.twitch.tv PRIVMSG #streamer :bonjour");

        verify(publisher, never()).publishEvent(any());
    }

    private IrcTwitchChatService buildService(ApplicationEventPublisher publisher) {
        return new IrcTwitchChatService(null, null, null, publisher, null);
    }
}
```

- [ ] **Lancer les tests pour vérifier qu'ils échouent (classe inexistante)**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.IrcTwitchChatServiceTest" 2>&1 | tail -5
```

Expected: compilation error — `IrcTwitchChatService` n'existe pas.

- [ ] **Créer `IrcTwitchChatService.java`**

```java
// src/main/java/fr/enimaloc/catapult/service/IrcTwitchChatService.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "irc")
@RequiredArgsConstructor
public class IrcTwitchChatService implements TwitchChatService {

    private static final String IRC_HOST = "irc.chat.twitch.tv";
    private static final int IRC_PORT = 6697;
    private static final String HELIX_BANS_URL = "https://api.twitch.tv/helix/moderation/bans";
    private static final String HELIX_USERS_URL = "https://api.twitch.tv/helix/users";
    private static final long MAX_RETRY_SECONDS = 60L;

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final Map<UUID, PrintWriter> writers = new ConcurrentHashMap<>();
    private final Map<UUID, Socket> sockets = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostConstruct
    public void init() {
        userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)
            .forEach(this::connect);
    }

    @PreDestroy
    public void shutdown() {
        sockets.forEach((userId, socket) -> {
            try { socket.close(); } catch (IOException ignored) {}
        });
        executor.shutdownNow();
    }

    @Override
    public void connect(UserAccount user) {
        disconnect(user);
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> openConnection(user, token, 1L),
                () -> log.debug("[IRC] No Twitch token for user {} — skipping connect", user.getId())
            );
    }

    @Override
    public void disconnect(UserAccount user) {
        writers.remove(user.getId());
        Socket socket = sockets.remove(user.getId());
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void openConnection(UserAccount user, OAuthToken token, long retryDelaySeconds) {
        executor.submit(() -> {
            try {
                SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault()
                    .createSocket(IRC_HOST, IRC_PORT);
                sockets.put(user.getId(), socket);

                String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
                PrintWriter writer = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
                writers.put(user.getId(), writer);

                writer.println("CAP REQ :twitch.tv/tags twitch.tv/commands");
                writer.println("PASS oauth:" + accessToken);
                writer.println("NICK " + user.getTwitchUsername().toLowerCase());
                writer.println("JOIN #" + user.getTwitchUsername().toLowerCase());

                log.info("[IRC] Connected for user {}", user.getTwitchUsername());

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        handleLine(user, writer, line);
                    }
                }
            } catch (IOException e) {
                if (sockets.containsKey(user.getId())) {
                    log.warn("[IRC] Connection lost for user {}: {}", user.getTwitchUsername(), e.getMessage());
                    sockets.remove(user.getId());
                    writers.remove(user.getId());
                    scheduleReconnect(user, token, retryDelaySeconds);
                }
            }
        });
    }

    private void scheduleReconnect(UserAccount user, OAuthToken token, long delaySeconds) {
        long nextDelay = Math.min(delaySeconds * 2, MAX_RETRY_SECONDS);
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
            if (!sockets.containsKey(user.getId())) {
                openConnection(user, token, nextDelay);
            }
        });
    }

    void handleLine(UserAccount user, PrintWriter writer, String line) {
        if (line.startsWith("PING")) {
            writer.println("PONG " + line.substring(5));
            return;
        }

        String tags = "";
        String rest = line;
        if (line.startsWith("@")) {
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx == -1) return;
            tags = line.substring(1, spaceIdx);
            rest = line.substring(spaceIdx + 1);
        }

        if (rest.startsWith(":")) {
            int spaceIdx = rest.indexOf(' ');
            if (spaceIdx == -1) return;
            rest = rest.substring(spaceIdx + 1);
        }

        if (!rest.startsWith("PRIVMSG")) return;

        int colonIdx = rest.indexOf(':', 1);
        if (colonIdx == -1) return;
        String message = rest.substring(colonIdx + 1).trim();

        if (!message.startsWith("!")) return;

        String[] parts = message.substring(1).split(" ", 2);
        String command = "!" + parts[0];
        List<String> args = parts.length > 1 ? List.of(parts[1].split(" ")) : List.of();

        eventPublisher.publishEvent(
            new ChatCommandEvent(this, user, command, args, extractRole(tags)));
    }

    static ChatCommandEvent.SenderRole extractRole(String tags) {
        for (String tag : tags.split(";")) {
            if (tag.startsWith("badges=")) {
                String badges = tag.substring("badges=".length());
                if (badges.contains("broadcaster/")) return ChatCommandEvent.SenderRole.BROADCASTER;
                if (badges.contains("moderator/")) return ChatCommandEvent.SenderRole.MODERATOR;
            }
        }
        return ChatCommandEvent.SenderRole.EVERYONE;
    }

    @Override
    public void sendMessage(UserAccount user, String message) {
        PrintWriter writer = writers.get(user.getId());
        if (writer == null) {
            log.warn("[IRC] Cannot send message for user {} — not connected", user.getId());
            return;
        }
        writer.println("PRIVMSG #" + user.getTwitchUsername().toLowerCase() + " :" + message);
    }

    @Override
    public void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        moderate(user, targetLogin, durationSeconds, reason);
    }

    @Override
    public void ban(UserAccount user, String targetLogin, String reason) {
        moderate(user, targetLogin, 0, reason);
    }

    @Override
    public void unban(UserAccount user, String targetLogin) {
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresent(token -> {
                String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
                String targetId = resolveUserId(accessToken, targetLogin);
                if (targetId == null) return;
                try {
                    restClient.delete()
                        .uri(HELIX_BANS_URL + "?broadcaster_id=" + user.getTwitchId()
                            + "&moderator_id=" + user.getTwitchId()
                            + "&user_id=" + targetId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Client-Id", twitchClientId)
                        .retrieve()
                        .toBodilessEntity();
                    log.info("[IRC] Unbanned {} for user {}", targetLogin, user.getId());
                } catch (Exception e) {
                    log.warn("[IRC] Failed to unban {} for user {}: {}",
                        targetLogin, user.getId(), e.getMessage());
                }
            });
    }

    private void moderate(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresent(token -> {
                String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
                String targetId = resolveUserId(accessToken, targetLogin);
                if (targetId == null) return;

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user_id", targetId);
                if (durationSeconds > 0) data.put("duration", durationSeconds);
                if (reason != null && !reason.isBlank()) data.put("reason", reason);

                try {
                    restClient.post()
                        .uri(HELIX_BANS_URL + "?broadcaster_id=" + user.getTwitchId()
                            + "&moderator_id=" + user.getTwitchId())
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Client-Id", twitchClientId)
                        .body(Map.of("data", data))
                        .retrieve()
                        .toBodilessEntity();
                    log.info("[IRC] Moderated {} ({}s) for user {}", targetLogin, durationSeconds, user.getId());
                } catch (Exception e) {
                    log.warn("[IRC] Moderation failed for {} on user {}: {}",
                        targetLogin, user.getId(), e.getMessage());
                }
            });
    }

    @SuppressWarnings("unchecked")
    private String resolveUserId(String accessToken, String login) {
        try {
            Map<String, Object> response = restClient.get()
                .uri(HELIX_USERS_URL + "?login=" + login)
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(Map.class);
            if (response == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return null;
            return (String) data.get(0).get("id");
        } catch (Exception e) {
            log.warn("[IRC] Failed to resolve user ID for '{}': {}", login, e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Lancer les tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.IrcTwitchChatServiceTest"
```

Expected: `5 tests completed, 0 failed`

- [ ] **Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/IrcTwitchChatService.java \
        src/test/java/fr/enimaloc/catapult/service/IrcTwitchChatServiceTest.java
git commit -m "feat: add IrcTwitchChatService with TLS connection and exponential backoff"
```

---

## Task 7 : `EventSubTwitchChatService`

**Files:**
- Créer: `src/main/java/fr/enimaloc/catapult/service/EventSubTwitchChatService.java`

- [ ] **Créer `EventSubTwitchChatService.java`**

```java
// src/main/java/fr/enimaloc/catapult/service/EventSubTwitchChatService.java
package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "eventsub")
@RequiredArgsConstructor
public class EventSubTwitchChatService implements TwitchChatService {

    private static final String WS_URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final String EVENTSUB_API = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String HELIX_CHAT_URL = "https://api.twitch.tv/helix/chat/messages";
    private static final String HELIX_BANS_URL = "https://api.twitch.tv/helix/moderation/bans";
    private static final String HELIX_USERS_URL = "https://api.twitch.tv/helix/users";
    private static final long MAX_RETRY_SECONDS = 60L;

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final Map<UUID, WebSocket> connections = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    public void init() {
        userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)
            .forEach(this::connect);
    }

    @PreDestroy
    public void shutdown() {
        connections.forEach((id, ws) -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"));
        connections.clear();
    }

    @Override
    public void connect(UserAccount user) {
        disconnect(user);
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> openConnection(user, token, WS_URL, 1L),
                () -> log.debug("[EventSub Chat] No token for user {}", user.getId())
            );
    }

    @Override
    public void disconnect(UserAccount user) {
        WebSocket ws = connections.remove(user.getId());
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bot disabled");
    }

    private void openConnection(UserAccount user, OAuthToken token, String wsUrl, long retryDelaySeconds) {
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new ChatListener(user, token, wsUrl, retryDelaySeconds))
            .whenComplete((ws, ex) -> {
                if (ex != null) {
                    log.warn("[EventSub Chat] Failed to connect for user {}: {}", user.getId(), ex.getMessage());
                    scheduleReconnect(user, token, wsUrl, retryDelaySeconds);
                } else {
                    connections.put(user.getId(), ws);
                }
            });
    }

    private void scheduleReconnect(UserAccount user, OAuthToken token, String wsUrl, long delaySeconds) {
        long nextDelay = Math.min(delaySeconds * 2, MAX_RETRY_SECONDS);
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
            if (!connections.containsKey(user.getId())) {
                openConnection(user, token, wsUrl, nextDelay);
            }
        });
    }

    void handleMessage(UserAccount user, OAuthToken token, String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageType = root.path("metadata").path("message_type").asText();
            switch (messageType) {
                case "session_welcome" -> {
                    String sessionId = root.path("payload").path("session").path("id").asText();
                    subscribe(user, token, sessionId);
                }
                case "session_reconnect" -> {
                    String reconnectUrl = root.path("payload").path("session").path("reconnect_url").asText();
                    log.info("[EventSub Chat] session_reconnect for user {}", user.getId());
                    openConnection(user, token, reconnectUrl, 1L);
                }
                case "notification" -> handleNotification(user, root);
                case "session_keepalive" -> log.trace("[EventSub Chat] keepalive for user {}", user.getId());
                case "revocation" -> log.warn("[EventSub Chat] subscription revoked for user {}", user.getId());
                default -> log.debug("[EventSub Chat] Unhandled message type '{}' for user {}",
                    messageType, user.getId());
            }
        } catch (Exception e) {
            log.error("[EventSub Chat] Failed to parse message for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private void handleNotification(UserAccount user, JsonNode root) {
        String subscriptionType = root.path("metadata").path("subscription_type").asText();
        JsonNode event = root.path("payload").path("event");

        if ("channel.chat.message".equals(subscriptionType)) {
            String text = event.path("message").path("text").asText();
            if (!text.startsWith("!")) return;

            String[] parts = text.substring(1).split(" ", 2);
            String command = "!" + parts[0];
            List<String> args = parts.length > 1 ? List.of(parts[1].split(" ")) : List.of();
            ChatCommandEvent.SenderRole role = extractRoleFromBadges(event.path("badges"));

            eventPublisher.publishEvent(new ChatCommandEvent(this, user, command, args, role));

        } else if ("channel.channel_points_custom_reward_redemption.add".equals(subscriptionType)) {
            String rewardTitle = event.path("reward").path("title").asText();
            eventPublisher.publishEvent(new ChatCommandEvent(this, user, "reward:" + rewardTitle,
                List.of(), ChatCommandEvent.SenderRole.EVERYONE));
            log.info("[EventSub Chat] Reward redeemed: '{}' for user {}", rewardTitle, user.getId());
        }
    }

    private ChatCommandEvent.SenderRole extractRoleFromBadges(JsonNode badges) {
        for (JsonNode badge : badges) {
            String setId = badge.path("set_id").asText();
            if ("broadcaster".equals(setId)) return ChatCommandEvent.SenderRole.BROADCASTER;
            if ("moderator".equals(setId)) return ChatCommandEvent.SenderRole.MODERATOR;
        }
        return ChatCommandEvent.SenderRole.EVERYONE;
    }

    private void subscribe(UserAccount user, OAuthToken token, String sessionId) {
        String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
        subscribeEvent(user, accessToken, sessionId, "channel.chat.message", "1",
            Map.of("broadcaster_user_id", user.getTwitchId(), "user_id", user.getTwitchId()));
        subscribeEvent(user, accessToken, sessionId,
            "channel.channel_points_custom_reward_redemption.add", "1",
            Map.of("broadcaster_user_id", user.getTwitchId()));
    }

    private void subscribeEvent(UserAccount user, String accessToken, String sessionId,
                                String type, String version, Map<String, String> condition) {
        try {
            restClient.post()
                .uri(EVENTSUB_API)
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-ID", twitchClientId)
                .body(Map.of("type", type, "version", version, "condition", condition,
                    "transport", Map.of("method", "websocket", "session_id", sessionId)))
                .retrieve()
                .toBodilessEntity();
            log.debug("[EventSub Chat] Subscribed to {} for user {}", type, user.getId());
        } catch (Exception e) {
            log.warn("[EventSub Chat] Failed to subscribe to {} for user {}: {}",
                type, user.getId(), e.getMessage());
        }
    }

    @Override
    public void sendMessage(UserAccount user, String message) {
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresent(token -> {
                String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
                try {
                    restClient.post()
                        .uri(HELIX_CHAT_URL)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Client-Id", twitchClientId)
                        .body(Map.of("broadcaster_id", user.getTwitchId(),
                            "sender_id", user.getTwitchId(), "message", message))
                        .retrieve()
                        .toBodilessEntity();
                } catch (Exception e) {
                    log.warn("[EventSub Chat] sendMessage failed for user {}: {}", user.getId(), e.getMessage());
                }
            });
    }

    @Override
    public void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        moderate(user, targetLogin, durationSeconds, reason);
    }

    @Override
    public void ban(UserAccount user, String targetLogin, String reason) {
        moderate(user, targetLogin, 0, reason);
    }

    @Override
    public void unban(UserAccount user, String targetLogin) {
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresent(token -> {
                String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
                String targetId = resolveUserId(accessToken, targetLogin);
                if (targetId == null) return;
                try {
                    restClient.delete()
                        .uri(HELIX_BANS_URL + "?broadcaster_id=" + user.getTwitchId()
                            + "&moderator_id=" + user.getTwitchId()
                            + "&user_id=" + targetId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Client-Id", twitchClientId)
                        .retrieve()
                        .toBodilessEntity();
                    log.info("[EventSub Chat] Unbanned {} for user {}", targetLogin, user.getId());
                } catch (Exception e) {
                    log.warn("[EventSub Chat] Unban failed for {} on user {}: {}",
                        targetLogin, user.getId(), e.getMessage());
                }
            });
    }

    private void moderate(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresent(token -> {
                String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
                String targetId = resolveUserId(accessToken, targetLogin);
                if (targetId == null) return;

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user_id", targetId);
                if (durationSeconds > 0) data.put("duration", durationSeconds);
                if (reason != null && !reason.isBlank()) data.put("reason", reason);

                try {
                    restClient.post()
                        .uri(HELIX_BANS_URL + "?broadcaster_id=" + user.getTwitchId()
                            + "&moderator_id=" + user.getTwitchId())
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Client-Id", twitchClientId)
                        .body(Map.of("data", data))
                        .retrieve()
                        .toBodilessEntity();
                    log.info("[EventSub Chat] Moderated {} ({}s) for user {}",
                        targetLogin, durationSeconds, user.getId());
                } catch (Exception e) {
                    log.warn("[EventSub Chat] Moderation failed for {} on user {}: {}",
                        targetLogin, user.getId(), e.getMessage());
                }
            });
    }

    @SuppressWarnings("unchecked")
    private String resolveUserId(String accessToken, String login) {
        try {
            Map<String, Object> response = restClient.get()
                .uri(HELIX_USERS_URL + "?login=" + login)
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(Map.class);
            if (response == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return null;
            return (String) data.get(0).get("id");
        } catch (Exception e) {
            log.warn("[EventSub Chat] Failed to resolve user ID for '{}': {}", login, e.getMessage());
            return null;
        }
    }

    private class ChatListener implements WebSocket.Listener {
        private final UserAccount user;
        private final OAuthToken token;
        private final String wsUrl;
        private final long retryDelaySeconds;
        private final StringBuilder buffer = new StringBuilder();

        ChatListener(UserAccount user, OAuthToken token, String wsUrl, long retryDelaySeconds) {
            this.user = user;
            this.token = token;
            this.wsUrl = wsUrl;
            this.retryDelaySeconds = retryDelaySeconds;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("[EventSub Chat] WebSocket opened for user {}", user.getId());
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(user, token, message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("[EventSub Chat] WebSocket closed for user {} ({}): {}",
                user.getId(), statusCode, reason);
            connections.remove(user.getId());
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                scheduleReconnect(user, token, wsUrl, retryDelaySeconds);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[EventSub Chat] WebSocket error for user {}: {}", user.getId(), error.getMessage());
            connections.remove(user.getId());
            scheduleReconnect(user, token, wsUrl, retryDelaySeconds);
        }
    }
}
```

- [ ] **Compiler**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Lancer tous les tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, tous les tests passent.

- [ ] **Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/EventSubTwitchChatService.java
git commit -m "feat: add EventSubTwitchChatService with channel.chat.message and reward redemption support"
```

---

## Vérification finale

- [ ] **Vérifier la configuration mock (défaut)**

Dans `application.properties`, s'assurer que `app.chat.provider=mock`. Lancer l'application — le démarrage ne doit pas tenter de connexion IRC/WebSocket.

- [ ] **Vérifier la sélection IRC**

Changer `app.chat.provider=irc`. Démarrer l'application. Les logs doivent montrer `[IRC] Connected for user ...` pour chaque utilisateur actif avec bot activé.

- [ ] **Vérifier la sélection EventSub**

Changer `app.chat.provider=eventsub`. Démarrer l'application. Les logs doivent montrer `[EventSub Chat] WebSocket opened for user ...` et `[EventSub Chat] Subscribed to channel.chat.message ...`.
