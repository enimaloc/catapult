package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.mock.twitch-eventsub", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class TwitchEventSubService implements EventSubService {

    private static final String WS_URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final String EVENTSUB_API = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
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

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    private final Map<UUID, WebSocket> connections = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    public void init() {
        userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)
            .forEach(this::connect);
    }

    @PreDestroy
    public void shutdown() {
        connections.forEach((userId, ws) -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown"));
        connections.clear();
    }

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        connect(event.getUser());
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
        httpClient
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
        String accessToken = resolveAccessToken(token, user);
        for (String eventType : List.of("stream.online", "stream.offline")) {
            try {
                postSubscription(accessToken, eventType, user.getTwitchId(), sessionId);
                log.debug("Subscribed to {} for user {}", eventType, user.getId());
            } catch (HttpClientErrorException.Unauthorized e) {
                log.debug("Token expired for user {}, refreshing", user.getId());
                String refreshed = refreshAccessToken(token, user);
                if (refreshed != null) {
                    try {
                        postSubscription(refreshed, eventType, user.getTwitchId(), sessionId);
                        log.debug("Subscribed to {} for user {} after token refresh", eventType, user.getId());
                    } catch (Exception retryEx) {
                        log.warn("Failed to subscribe to {} for user {} after refresh: {}", eventType, user.getId(), retryEx.getMessage());
                    }
                } else {
                    log.warn("Failed to subscribe to {} for user {}: token refresh failed", eventType, user.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to subscribe to {} for user {}: {}", eventType, user.getId(), e.getMessage());
            }
        }
    }

    private String resolveAccessToken(OAuthToken token, UserAccount user) {
        if (token.getExpiresAt() != null && Instant.now().isAfter(token.getExpiresAt())) {
            log.debug("Token for user {} is expired, proactively refreshing", user.getId());
            String refreshed = refreshAccessToken(token, user);
            if (refreshed != null) return refreshed;
        }
        return tokenEncryptionService.decrypt(token.getAccessToken());
    }

    @SuppressWarnings("unchecked")
    private String refreshAccessToken(OAuthToken token, UserAccount user) {
        if (token.getRefreshToken() == null) {
            log.warn("No refresh token stored for user {} — cannot refresh", user.getId());
            return null;
        }
        String refreshToken = tokenEncryptionService.decrypt(token.getRefreshToken());
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            form.add("client_id", twitchClientId);
            form.add("client_secret", twitchClientSecret);
            Map<String, Object> response = restClient.post()
                .uri(TWITCH_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
            if (response == null) return null;

            String newAccess = (String) response.get("access_token");
            String newRefresh = (String) response.get("refresh_token");
            Number expiresIn = (Number) response.get("expires_in");

            token.setAccessToken(tokenEncryptionService.encrypt(newAccess));
            if (newRefresh != null) token.setRefreshToken(tokenEncryptionService.encrypt(newRefresh));
            if (expiresIn != null) token.setExpiresAt(Instant.now().plusSeconds(expiresIn.longValue()));
            oAuthTokenRepository.save(token);
            log.debug("Refreshed Twitch token for user {}", user.getId());
            return newAccess;
        } catch (Exception e) {
            log.warn("Failed to refresh Twitch token for user {}: {}", user.getId(), e.getMessage());
            return null;
        }
    }

    private void postSubscription(String accessToken, String eventType, String twitchId, String sessionId) {
        restClient.post()
            .uri(EVENTSUB_API)
            .header("Authorization", "Bearer " + accessToken)
            .header("Client-ID", twitchClientId)
            .header("Content-Type", "application/json")
            .body(Map.of(
                "type", eventType,
                "version", "1",
                "condition", Map.of("broadcaster_user_id", twitchId),
                "transport", Map.of("method", "websocket", "session_id", sessionId)
            ))
            .retrieve()
            .toBodilessEntity();
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
