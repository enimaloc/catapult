# Live Check via Twitch EventSub WebSocket Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Only update the Twitch channel when the user is live; store pending game changes when offline; apply them on `stream.online`; reset to the default category on `stream.offline`.

**Architecture:** A new `TwitchEventSubService` opens one `java.net.http.WebSocket` per active user to `wss://eventsub.wss.twitch.tv/ws`, subscribes to `stream.online`/`stream.offline`, and publishes Spring events. A `StreamStateService` stores live status and pending bindings in memory. `GameEventListener` gates `updateChannel()` behind `isLive()` and handles the stream lifecycle.

**Tech Stack:** Java 17, Spring Boot 4.0.4, `java.net.http.WebSocket`, Jackson `ObjectMapper`, JUnit 5 + Mockito

---

## File Map

**Create:**
- `src/main/java/fr/esportline/catapult/service/StreamStateService.java`
- `src/main/java/fr/esportline/catapult/service/TwitchEventSubService.java`
- `src/main/java/fr/esportline/catapult/event/StreamOnlineEvent.java`
- `src/main/java/fr/esportline/catapult/event/StreamOfflineEvent.java`
- `src/test/java/fr/esportline/catapult/service/StreamStateServiceTest.java`
- `src/test/java/fr/esportline/catapult/service/TwitchEventSubServiceTest.java`
- `src/test/java/fr/esportline/catapult/event/GameEventListenerLiveCheckTest.java`

**Modify:**
- `src/main/java/fr/esportline/catapult/service/TwitchService.java` — add `resetToDefault(UserAccount)`
- `src/main/java/fr/esportline/catapult/event/GameEventListener.java` — live check + stream event handlers
- `src/main/java/fr/esportline/catapult/web/AppController.java` — inject StreamStateService + TwitchEventSubService, expose `isLive`
- `src/main/resources/templates/fragments/status.html` — add live/offline indicator

---

## Task 1: StreamStateService

**Files:**
- Create: `src/main/java/fr/esportline/catapult/service/StreamStateService.java`
- Test: `src/test/java/fr/esportline/catapult/service/StreamStateServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/fr/esportline/catapult/service/StreamStateServiceTest.java
package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreamStateServiceTest {

    private StreamStateService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new StreamStateService();
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void isLive_defaultsFalse() {
        assertThat(service.isLive(user)).isFalse();
    }

    @Test
    void setLive_updatesState() {
        service.setLive(user, true);
        assertThat(service.isLive(user)).isTrue();
        service.setLive(user, false);
        assertThat(service.isLive(user)).isFalse();
    }

    @Test
    void storePending_getPending_roundtrip() {
        GameBinding binding = new GameBinding();
        service.storePending(user, binding);
        assertThat(service.getPending(user)).contains(binding);
    }

    @Test
    void getPending_emptyByDefault() {
        assertThat(service.getPending(user)).isEmpty();
    }

    @Test
    void clearPending_removesBinding() {
        service.storePending(user, new GameBinding());
        service.clearPending(user);
        assertThat(service.getPending(user)).isEmpty();
    }

    @Test
    void clear_removesLiveAndPending() {
        service.setLive(user, true);
        service.storePending(user, new GameBinding());
        service.clear(user);
        assertThat(service.isLive(user)).isFalse();
        assertThat(service.getPending(user)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew test --tests "fr.esportline.catapult.service.StreamStateServiceTest"
```

Expected: compilation error — `StreamStateService` does not exist.

- [ ] **Step 3: Implement StreamStateService**

```java
// src/main/java/fr/esportline/catapult/service/StreamStateService.java
package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StreamStateService {

    private final Map<UUID, Boolean> liveStatus = new ConcurrentHashMap<>();
    private final Map<UUID, GameBinding> pendingBinding = new ConcurrentHashMap<>();

    public boolean isLive(UserAccount user) {
        return liveStatus.getOrDefault(user.getId(), false);
    }

    public void setLive(UserAccount user, boolean live) {
        liveStatus.put(user.getId(), live);
    }

    public void storePending(UserAccount user, GameBinding binding) {
        pendingBinding.put(user.getId(), binding);
    }

    public Optional<GameBinding> getPending(UserAccount user) {
        return Optional.ofNullable(pendingBinding.get(user.getId()));
    }

    public void clearPending(UserAccount user) {
        pendingBinding.remove(user.getId());
    }

    public void clear(UserAccount user) {
        liveStatus.remove(user.getId());
        pendingBinding.remove(user.getId());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "fr.esportline.catapult.service.StreamStateServiceTest"
```

Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/esportline/catapult/service/StreamStateService.java \
        src/test/java/fr/esportline/catapult/service/StreamStateServiceTest.java
git commit -m "feat: add StreamStateService to track live status and pending bindings"
```

---

## Task 2: StreamOnlineEvent + StreamOfflineEvent

**Files:**
- Create: `src/main/java/fr/esportline/catapult/event/StreamOnlineEvent.java`
- Create: `src/main/java/fr/esportline/catapult/event/StreamOfflineEvent.java`

No unit test needed — these are simple value objects following the same pattern as `NoGameDetectedEvent`.

- [ ] **Step 1: Create StreamOnlineEvent**

```java
// src/main/java/fr/esportline/catapult/event/StreamOnlineEvent.java
package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class StreamOnlineEvent extends ApplicationEvent {

    private final UserAccount user;

    public StreamOnlineEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
```

- [ ] **Step 2: Create StreamOfflineEvent**

```java
// src/main/java/fr/esportline/catapult/event/StreamOfflineEvent.java
package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class StreamOfflineEvent extends ApplicationEvent {

    private final UserAccount user;

    public StreamOfflineEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/esportline/catapult/event/StreamOnlineEvent.java \
        src/main/java/fr/esportline/catapult/event/StreamOfflineEvent.java
git commit -m "feat: add StreamOnlineEvent and StreamOfflineEvent"
```

---

## Task 3: TwitchService.resetToDefault()

**Files:**
- Modify: `src/main/java/fr/esportline/catapult/service/TwitchService.java`
- Test: `src/test/java/fr/esportline/catapult/service/TwitchServiceTest.java` (add tests)

- [ ] **Step 1: Write failing tests**

Add these test methods inside `TwitchServiceTest` (after the existing tests):

```java
// In TwitchServiceTest — add these methods

@Test
void resetToDefault_noSettings_doesNotCallApi() {
    when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.empty());

    twitchService.resetToDefault(user);

    verify(restClient, never()).patch();
}

@Test
void resetToDefault_noGameIdConfigured_doesNotCallApi() {
    UserSettings settings = new UserSettings();
    settings.setNoGameTwitchGameId(null);
    when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

    twitchService.resetToDefault(user);

    verify(restClient, never()).patch();
}

@Test
void resetToDefault_gameIdConfigured_patchesTwitchChannel() {
    UserSettings settings = new UserSettings();
    settings.setNoGameTwitchGameId("12345");

    when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
    // token stub already configured in @BeforeEach

    when(restClient.patch()).thenReturn(patchSpec);
    when(patchSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
    when(bodySpec.body(any())).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity()).thenReturn(null);

    twitchService.resetToDefault(user);

    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
    verify(bodySpec).body(bodyCaptor.capture());
    assertThat(bodyCaptor.getValue()).containsEntry("game_id", "12345");
}
```

Note: `TwitchServiceTest.@BeforeEach` already stubs `oAuthTokenRepository.findByUserAndProvider` and `tokenEncryptionService.decrypt`. Check the existing test file to confirm — those stubs must be present for `resetToDefault_gameIdConfigured_patchesTwitchChannel` to work. If they are not in `@BeforeEach`, add them there.

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew test --tests "fr.esportline.catapult.service.TwitchServiceTest.resetToDefault*"
```

Expected: compilation error — `resetToDefault` does not exist on `TwitchService`.

- [ ] **Step 3: Add resetToDefault to TwitchService**

Add these two methods to `TwitchService.java` after the `buildCclPayload` method:

```java
public void resetToDefault(UserAccount user) {
    userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
        if (settings.getNoGameTwitchGameId() == null || settings.getNoGameTwitchGameId().isBlank()) {
            log.debug("No default category configured for user {} — skipping reset", user.getId());
            return;
        }
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> doResetToDefault(user, settings, token),
                () -> log.warn("No Twitch token for user {} during reset", user.getId())
            );
    });
}

private void doResetToDefault(UserAccount user, UserSettings settings, OAuthToken token) {
    String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
    Map<String, Object> body = Map.of("game_id", settings.getNoGameTwitchGameId());
    try {
        restClient.patch()
            .uri(TWITCH_API_URL + "/channels?broadcaster_id=" + user.getTwitchId())
            .header("Authorization", "Bearer " + accessToken)
            .header("Client-ID", twitchClientId)
            .header("Content-Type", "application/json")
            .body(body)
            .retrieve()
            .toBodilessEntity();
        log.info("Twitch channel reset to default for user {} — game_id={}",
            user.getId(), settings.getNoGameTwitchGameId());
    } catch (HttpClientErrorException e) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            log.warn("Twitch token invalid for user {} during reset — pausing bot", user.getId());
            user.setBotEnabled(false);
            userAccountRepository.save(user);
        } else {
            log.error("Twitch API error during reset for user {}: {} {}",
                user.getId(), e.getStatusCode(), e.getMessage());
        }
    } catch (Exception e) {
        log.error("Unexpected error resetting Twitch channel for user {}", user.getId(), e);
    }
}
```

Also add this import at the top of `TwitchService.java` if not already present:

```java
import fr.esportline.catapult.domain.UserSettings;
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "fr.esportline.catapult.service.TwitchServiceTest"
```

Expected: all tests PASS (existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/esportline/catapult/service/TwitchService.java \
        src/test/java/fr/esportline/catapult/service/TwitchServiceTest.java
git commit -m "feat: add TwitchService.resetToDefault() to reset channel category on stream end"
```

---

## Task 4: TwitchEventSubService

**Files:**
- Create: `src/main/java/fr/esportline/catapult/service/TwitchEventSubService.java`
- Test: `src/test/java/fr/esportline/catapult/service/TwitchEventSubServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/esportline/catapult/service/TwitchEventSubServiceTest.java
package fr.esportline.catapult.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.event.StreamOfflineEvent;
import fr.esportline.catapult.event.StreamOnlineEvent;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchEventSubServiceTest {

    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private StreamStateService streamStateService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RestClient restClient;

    @Mock private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock private RestClient.RequestBodySpec postBodySpec;
    @Mock private RestClient.ResponseSpec postResponseSpec;

    @InjectMocks private TwitchEventSubService service;

    private UserAccount user;
    private OAuthToken token;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("broadcaster-123");

        token = new OAuthToken();
        token.setAccessToken("encrypted");

        when(tokenEncryptionService.decrypt("encrypted")).thenReturn("decrypted-token");
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));

        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(anyString(), anyString())).thenReturn(postBodySpec);
        when(postBodySpec.body(any())).thenReturn((RestClient.RequestHeadersSpec) postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        ReflectionTestUtils.setField(service, "twitchClientId", "test-client-id");
    }

    @Test
    void handleMessage_streamOnline_setsLiveTrueAndPublishesEvent() {
        String message = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "stream.online" },
              "payload": { "event": {} }
            }
            """;

        service.handleMessage(user, token, message);

        verify(streamStateService).setLive(user, true);
        ArgumentCaptor<StreamOnlineEvent> captor = ArgumentCaptor.forClass(StreamOnlineEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void handleMessage_streamOffline_setsLiveFalseAndPublishesEvent() {
        String message = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "stream.offline" },
              "payload": { "event": {} }
            }
            """;

        service.handleMessage(user, token, message);

        verify(streamStateService).setLive(user, false);
        ArgumentCaptor<StreamOfflineEvent> captor = ArgumentCaptor.forClass(StreamOfflineEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void handleMessage_sessionWelcome_subscribesToStreamOnlineAndOffline() {
        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc" } }
            }
            """;

        service.handleMessage(user, token, message);

        // One POST for stream.online, one for stream.offline
        verify(restClient, times(2)).post();
    }

    @Test
    void handleMessage_invalidJson_doesNotThrow() {
        assertThatNoException().isThrownBy(
            () -> service.handleMessage(user, token, "not-json")
        );
    }

    @Test
    void handleMessage_unknownMessageType_doesNotThrow() {
        String message = """
            { "metadata": { "message_type": "session_keepalive" }, "payload": {} }
            """;

        assertThatNoException().isThrownBy(
            () -> service.handleMessage(user, token, message)
        );
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew test --tests "fr.esportline.catapult.service.TwitchEventSubServiceTest"
```

Expected: compilation error — `TwitchEventSubService` does not exist.

- [ ] **Step 3: Implement TwitchEventSubService**

```java
// src/main/java/fr/esportline/catapult/service/TwitchEventSubService.java
package fr.esportline.catapult.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.event.StreamOfflineEvent;
import fr.esportline.catapult.event.StreamOnlineEvent;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.security.TokenEncryptionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TwitchEventSubService {

    private static final String WS_URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final String EVENTSUB_API = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final long MAX_RETRY_SECONDS = 60L;

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final StreamStateService streamStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final Map<UUID, WebSocket> connections = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)
            .forEach(this::connect);
    }

    public void connect(UserAccount user) {
        disconnect(user);
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> openConnection(user, token, WS_URL, 1L),
                () -> log.debug("No Twitch token for user {} — skipping EventSub connect", user.getId())
            );
    }

    public void disconnect(UserAccount user) {
        WebSocket ws = connections.remove(user.getId());
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bot disabled");
        }
        streamStateService.clear(user);
    }

    private void openConnection(UserAccount user, OAuthToken token, String wsUrl, long retryDelaySeconds) {
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new EventSubListener(user, token, wsUrl, retryDelaySeconds))
            .whenComplete((ws, ex) -> {
                if (ex != null) {
                    log.warn("Failed to open EventSub WebSocket for user {}: {}", user.getId(), ex.getMessage());
                    scheduleReconnect(user, token, wsUrl, retryDelaySeconds);
                } else {
                    connections.put(user.getId(), ws);
                }
            });
    }

    private void scheduleReconnect(UserAccount user, OAuthToken token, String wsUrl, long delaySeconds) {
        long nextDelay = Math.min(delaySeconds * 2, MAX_RETRY_SECONDS);
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
            if (connections.containsKey(user.getId())) return;
            openConnection(user, token, wsUrl, nextDelay);
        });
    }

    // Package-private for testing
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
                    log.info("EventSub session_reconnect for user {}", user.getId());
                    openConnection(user, token, reconnectUrl, 1L);
                }
                case "notification" -> {
                    String subscriptionType = root.path("metadata").path("subscription_type").asText();
                    if ("stream.online".equals(subscriptionType)) {
                        streamStateService.setLive(user, true);
                        eventPublisher.publishEvent(new StreamOnlineEvent(this, user));
                    } else if ("stream.offline".equals(subscriptionType)) {
                        streamStateService.setLive(user, false);
                        eventPublisher.publishEvent(new StreamOfflineEvent(this, user));
                    }
                }
                case "session_keepalive" -> log.trace("EventSub keepalive for user {}", user.getId());
                case "revocation" -> log.warn("EventSub subscription revoked for user {}", user.getId());
                default -> log.debug("Unhandled EventSub message type '{}' for user {}", messageType, user.getId());
            }
        } catch (Exception e) {
            log.error("Failed to parse EventSub message for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private void subscribe(UserAccount user, OAuthToken token, String sessionId) {
        String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
        for (String eventType : List.of("stream.online", "stream.offline")) {
            try {
                restClient.post()
                    .uri(EVENTSUB_API)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Client-ID", twitchClientId)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                        "type", eventType,
                        "version", "1",
                        "condition", Map.of("broadcaster_user_id", user.getTwitchId()),
                        "transport", Map.of("method", "websocket", "session_id", sessionId)
                    ))
                    .retrieve()
                    .toBodilessEntity();
                log.debug("Subscribed to {} for user {}", eventType, user.getId());
            } catch (Exception e) {
                log.warn("Failed to subscribe to {} for user {}: {}", eventType, user.getId(), e.getMessage());
            }
        }
    }

    private class EventSubListener implements WebSocket.Listener {
        private final UserAccount user;
        private final OAuthToken token;
        private final String wsUrl;
        private final long retryDelaySeconds;
        private final StringBuilder buffer = new StringBuilder();

        EventSubListener(UserAccount user, OAuthToken token, String wsUrl, long retryDelaySeconds) {
            this.user = user;
            this.token = token;
            this.wsUrl = wsUrl;
            this.retryDelaySeconds = retryDelaySeconds;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("EventSub WebSocket opened for user {}", user.getId());
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
            log.debug("EventSub WebSocket closed for user {} ({}): {}", user.getId(), statusCode, reason);
            connections.remove(user.getId());
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                scheduleReconnect(user, token, wsUrl, retryDelaySeconds);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("EventSub WebSocket error for user {}: {}", user.getId(), error.getMessage());
            connections.remove(user.getId());
            scheduleReconnect(user, token, wsUrl, retryDelaySeconds);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "fr.esportline.catapult.service.TwitchEventSubServiceTest"
```

Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/esportline/catapult/service/TwitchEventSubService.java \
        src/test/java/fr/esportline/catapult/service/TwitchEventSubServiceTest.java
git commit -m "feat: add TwitchEventSubService with WebSocket EventSub connection management"
```

---

## Task 5: GameEventListener live check

**Files:**
- Modify: `src/main/java/fr/esportline/catapult/event/GameEventListener.java`
- Test: `src/test/java/fr/esportline/catapult/event/GameEventListenerLiveCheckTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/esportline/catapult/event/GameEventListenerLiveCheckTest.java
package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.getter.DetectedGame;
import fr.esportline.catapult.service.BindingService;
import fr.esportline.catapult.service.StreamStateService;
import fr.esportline.catapult.service.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameEventListenerLiveCheckTest {

    @Mock private BindingService bindingService;
    @Mock private TwitchService twitchService;
    @Mock private StreamStateService streamStateService;

    @InjectMocks private GameEventListener listener;

    private UserAccount user;
    private GameBinding binding;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        binding = new GameBinding();
        binding.setStatus(GameBinding.Status.AUTO);

        DetectedGame game = new DetectedGame("game-1", "Half-Life", GameBinding.SourceType.STEAM);
        when(bindingService.resolveOrCreate(eq(user), any())).thenReturn(binding);
    }

    @Test
    void onGameDetected_whenLive_callsUpdateChannel() {
        when(streamStateService.isLive(user)).thenReturn(true);
        DetectedGame game = new DetectedGame("g1", "Game", GameBinding.SourceType.STEAM);

        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(twitchService).updateChannel(user, binding);
        verify(streamStateService, never()).storePending(any(), any());
    }

    @Test
    void onGameDetected_whenNotLive_storesPendingAndSkipsUpdate() {
        when(streamStateService.isLive(user)).thenReturn(false);
        DetectedGame game = new DetectedGame("g1", "Game", GameBinding.SourceType.STEAM);

        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(streamStateService).storePending(user, binding);
        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void onStreamOnline_withPendingBinding_appliesItAndClears() {
        when(streamStateService.getPending(user)).thenReturn(Optional.of(binding));

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(twitchService).updateChannel(user, binding);
        verify(streamStateService).clearPending(user);
    }

    @Test
    void onStreamOnline_withoutPendingBinding_doesNothing() {
        when(streamStateService.getPending(user)).thenReturn(Optional.empty());

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void onStreamOffline_callsResetToDefaultAndClearsPending() {
        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        verify(twitchService).resetToDefault(user);
        verify(streamStateService).clearPending(user);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew test --tests "fr.esportline.catapult.event.GameEventListenerLiveCheckTest"
```

Expected: compilation errors — `StreamStateService` not injected, `onStreamOnline`/`onStreamOffline` missing.

- [ ] **Step 3: Modify GameEventListener**

Replace the entire file content with:

```java
// src/main/java/fr/esportline/catapult/event/GameEventListener.java
package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.UserSettingsRepository;
import fr.esportline.catapult.service.BindingService;
import fr.esportline.catapult.service.StreamStateService;
import fr.esportline.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventListener {

    private final BindingService bindingService;
    private final TwitchService twitchService;
    private final UserSettingsRepository userSettingsRepository;
    private final StreamStateService streamStateService;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("GameDetectedEvent for user {}: {}", user.getId(), event.getDetectedGame().getSourceName());

        GameBinding binding = bindingService.resolveOrCreate(user, event.getDetectedGame());

        if (binding.getStatus() == GameBinding.Status.INCOMPLETE || binding.isIgnored()) {
            log.debug("Binding is {} — skipping update for user {}", binding.getStatus(), user.getId());
            return;
        }

        if (streamStateService.isLive(user)) {
            twitchService.updateChannel(user, binding);
        } else {
            streamStateService.storePending(user, binding);
            log.debug("User {} not live — stored pending binding for game {}",
                user.getId(), binding.getSourceName());
        }
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("NoGameDetectedEvent for user {}", user.getId());

        if (!streamStateService.isLive(user)) {
            log.debug("User {} not live — skipping no-game fallback", user.getId());
            return;
        }

        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.getNoGameTwitchGameId() != null && !settings.getNoGameTwitchGameId().isBlank()) {
                GameBinding fallbackBinding = new GameBinding();
                fallbackBinding.setUser(user);
                fallbackBinding.setSourceType(GameBinding.SourceType.MANUAL);
                fallbackBinding.setSourceName("no-game-fallback");
                fallbackBinding.setTwitchGameId(settings.getNoGameTwitchGameId());
                fallbackBinding.setTwitchGameName(settings.getNoGameTwitchGameName());
                fallbackBinding.setStatus(GameBinding.Status.MANUAL);

                twitchService.updateChannel(user, fallbackBinding);
            }
        });
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOnlineEvent for user {}", user.getId());
        streamStateService.getPending(user).ifPresent(binding -> {
            twitchService.updateChannel(user, binding);
            streamStateService.clearPending(user);
        });
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOfflineEvent for user {}", user.getId());
        twitchService.resetToDefault(user);
        streamStateService.clearPending(user);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "fr.esportline.catapult.event.GameEventListenerLiveCheckTest"
```

Expected: all 5 tests PASS.

- [ ] **Step 5: Run all tests to catch regressions**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/fr/esportline/catapult/event/GameEventListener.java \
        src/test/java/fr/esportline/catapult/event/GameEventListenerLiveCheckTest.java
git commit -m "feat: gate Twitch updates behind live check, apply pending on stream.online"
```

---

## Task 6: AppController + status.html

**Files:**
- Modify: `src/main/java/fr/esportline/catapult/web/AppController.java`
- Modify: `src/main/resources/templates/fragments/status.html`

- [ ] **Step 1: Add StreamStateService + TwitchEventSubService to AppController**

In `AppController.java`:

1. Add two new fields after `private final AccountService accountService;`:

```java
private final StreamStateService streamStateService;
private final TwitchEventSubService twitchEventSubService;
```

2. Add `isLive` to the model in the `app()` method, after the line `model.addAttribute("botEnabled", user.isBotEnabled());`:

```java
model.addAttribute("isLive", streamStateService.isLive(user));
```

3. Add `isLive` to the model in `fragmentStatus()`, after `model.addAttribute("botEnabled", user.isBotEnabled());`:

```java
model.addAttribute("isLive", streamStateService.isLive(user));
```

4. Replace the `toggleBot()` method body:

```java
@PostMapping("/settings/bot")
public String toggleBot(@AuthenticationPrincipal CatapultOAuth2User principal,
                        @RequestParam boolean enabled) {
    UserAccount user = principal.getUserAccount();
    user.setBotEnabled(enabled);
    if (enabled) {
        twitchEventSubService.connect(user);
    } else {
        twitchEventSubService.disconnect(user);
    }
    return "redirect:/app";
}
```

- [ ] **Step 2: Update status.html to add live indicator**

Replace the full content of `src/main/resources/templates/fragments/status.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="status"
     hx-get="/fragments/status"
     hx-trigger="every 3s"
     hx-swap="outerHTML">
    <div class="card">
        <h3>État du bot</h3>
        <div th:if="${botEnabled}" class="status status-active">
            <span class="status-dot"></span> Actif
        </div>
        <div th:unless="${botEnabled}" class="status status-inactive">
            <span class="status-dot"></span> Inactif
        </div>
    </div>
    <div class="card">
        <h3>Stream</h3>
        <div th:if="${isLive}" class="status status-active">
            <span class="status-dot"></span> En live
        </div>
        <div th:unless="${isLive}" class="status status-inactive">
            <span class="status-dot"></span> Hors ligne
        </div>
    </div>
    <div class="card">
        <h3>Jeu en cours</h3>
        <div th:if="${currentGame != null}">
            <p>
                <strong th:text="${currentGame.sourceName}"></strong>
                <span class="badge" th:text="${currentGame.sourceType}"></span>
            </p>
        </div>
        <div th:unless="${currentGame != null}">
            <p class="text-muted">Aucun jeu détecté</p>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 3: Compile and run all tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/esportline/catapult/web/AppController.java \
        src/main/resources/templates/fragments/status.html
git commit -m "feat: expose live status in UI and connect EventSub on bot toggle"
```

---

## Self-Review Checklist

- [x] **Spec coverage:**
  - `StreamStateService` → Task 1
  - `StreamOnlineEvent` / `StreamOfflineEvent` → Task 2
  - `TwitchService.resetToDefault()` → Task 3
  - `TwitchEventSubService` (WebSocket + subscribe + reconnect) → Task 4
  - `GameEventListener` live gate + online/offline handlers → Task 5
  - `AppController` `isLive` + bot toggle connect/disconnect → Task 6
  - `status.html` live indicator → Task 6
  - `onNoGameDetected` also gated behind live check → Task 5

- [x] **No placeholders** — all steps include complete code.

- [x] **Type consistency:**
  - `StreamStateService.clear(UserAccount)` used in Task 4 (`disconnect`) and defined in Task 1.
  - `StreamStateService.getPending(UserAccount)` returns `Optional<GameBinding>` — used correctly in Task 5.
  - `TwitchService.resetToDefault(UserAccount)` defined in Task 3, called in Task 5.
  - `TwitchEventSubService.connect(UserAccount)` / `disconnect(UserAccount)` defined in Task 4, called in Task 6.
  - `StreamOnlineEvent(Object, UserAccount)` / `StreamOfflineEvent(Object, UserAccount)` defined in Task 2, used in Task 4 and Task 5.
