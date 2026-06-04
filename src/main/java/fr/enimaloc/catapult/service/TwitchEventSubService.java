package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.event.ChannelCclChangedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String HELIX_CHANNELS_API = "https://api.twitch.tv/helix/channels";
    private static final long MAX_RETRY_SECONDS = 60L;

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final TwitchTokenService twitchTokenService;
    private final StreamStateService streamStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final Map<UUID, WebSocket> connections = new ConcurrentHashMap<>();
    private final Map<UUID, ChannelState> channelStates = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private record ChannelState(String categoryId, String categoryName, Set<String> cclIds) {}

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
        channelStates.remove(user.getId());
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
                    switch (subscriptionType) {
                        case "stream.online" -> {
                            streamStateService.setLive(user, true);
                            eventPublisher.publishEvent(new StreamOnlineEvent(this, user));
                        }
                        case "stream.offline" -> {
                            streamStateService.setLive(user, false);
                            eventPublisher.publishEvent(new StreamOfflineEvent(this, user));
                        }
                        case "channel.update" -> handleChannelUpdate(user, root.path("payload").path("event"));
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
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        var subscriptions = List.of(
            Map.entry("stream.online", "1"),
            Map.entry("stream.offline", "1"),
            Map.entry("channel.update", "2")
        );
        for (var sub : subscriptions) {
            String eventType = sub.getKey();
            String version = sub.getValue();
            try {
                postSubscription(accessToken, eventType, version, user.getTwitchId(), sessionId);
                log.debug("Subscribed to {} for user {}", eventType, user.getId());
            } catch (HttpClientErrorException.Unauthorized e) {
                log.debug("Token expired for user {}, refreshing", user.getId());
                String refreshed = twitchTokenService.refreshAccessToken(token, user);
                if (refreshed != null) {
                    accessToken = refreshed;
                    try {
                        postSubscription(refreshed, eventType, version, user.getTwitchId(), sessionId);
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
        initChannelState(user, accessToken);
    }

    private void handleChannelUpdate(UserAccount user, JsonNode event) {
        String newCategoryId = event.path("category_id").asText();
        String newCategoryName = event.path("category_name").asText();
        Set<String> newCcls = new HashSet<>();
        event.path("content_classification_labels").forEach(l -> newCcls.add(l.asText()));

        ChannelState previous = channelStates.put(user.getId(), new ChannelState(newCategoryId, newCategoryName, newCcls));
        if (previous == null) return;

        if (!newCategoryId.equals(previous.categoryId())) {
            eventPublisher.publishEvent(new ChannelCategoryChangedEvent(this, user, newCategoryId, newCategoryName));
        }
        if (!newCcls.equals(previous.cclIds())) {
            eventPublisher.publishEvent(new ChannelCclChangedEvent(this, user, List.copyOf(newCcls)));
        }
    }

    private void initChannelState(UserAccount user, String accessToken) {
        try {
            JsonNode response = restClient.get()
                .uri(HELIX_CHANNELS_API + "?broadcaster_id=" + user.getTwitchId())
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-ID", twitchClientId)
                .retrieve()
                .body(JsonNode.class);
            if (response == null || !response.has("data") || response.path("data").isEmpty()) return;

            JsonNode data = response.path("data").get(0);
            String categoryId = data.path("game_id").asText();
            String categoryName = data.path("game_name").asText();
            Set<String> cclIds = new HashSet<>();
            for (JsonNode label : data.path("content_classification_labels")) {
                if (label.path("is_enabled").asBoolean()) {
                    cclIds.add(label.path("id").asText());
                }
            }
            channelStates.put(user.getId(), new ChannelState(categoryId, categoryName, cclIds));
            log.debug("Initialized channel state for user {}: category={}, ccls={}", user.getId(), categoryId, cclIds);
        } catch (Exception e) {
            log.warn("Failed to initialize channel state for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private void postSubscription(String accessToken, String eventType, String version, String twitchId, String sessionId) {
        restClient.post()
            .uri(EVENTSUB_API)
            .header("Authorization", "Bearer " + accessToken)
            .header("Client-ID", twitchClientId)
            .header("Content-Type", "application/json")
            .body(Map.of(
                "type", eventType,
                "version", version,
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
