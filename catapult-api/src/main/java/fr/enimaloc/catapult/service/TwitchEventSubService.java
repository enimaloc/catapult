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
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.mock.twitch-eventsub", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class TwitchEventSubService implements EventSubService {

    private static final String WS_URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final String EVENTSUB_API = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String HELIX_CHANNELS_API = "https://api.twitch.tv/helix/channels";
    private static final String HELIX_STREAMS_API = "https://api.twitch.tv/helix/streams";
    private static final long MAX_RETRY_SECONDS = 60L;
    private static final long DEFAULT_KEEPALIVE_SECONDS = 10L;
    private static final double KEEPALIVE_GRACE = 1.5;

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final TwitchTokenService twitchTokenService;
    private final StreamStateService streamStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TwitchatNotifier twitchatNotifier;
    private final CatapultCategoryChangeStateService categoryChangeStateService;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final Map<UUID, WebSocket> connections = new ConcurrentHashMap<>();
    private final Map<UUID, ChannelState> channelStates = new ConcurrentHashMap<>();
    private final Set<UUID> channelStateWarnedUsers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> keepaliveTimeoutSeconds = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> watchdogs = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService watchdogExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "twitch-eventsub-watchdog");
        t.setDaemon(true);
        return t;
    });

    private record ChannelState(String categoryId, String categoryName, Set<String> cclIds) {}

    @PostConstruct
    public void init() {
        userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)
            .forEach(this::connect);
    }

    @PreDestroy
    public void shutdown() {
        watchdogs.values().forEach(f -> f.cancel(false));
        watchdogs.clear();
        watchdogExecutor.shutdownNow();
        // De-register before closing so onClose sees a foreign socket and never reconnects
        connections.keySet().forEach(userId -> {
            WebSocket ws = connections.remove(userId);
            if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
        });
    }

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        connect(event.getUser());
    }

    public void connect(UserAccount user) {
        disconnect(user);
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> openConnection(user, token, WS_URL, 1L, false),
                () -> log.debug("No Twitch token for user {} — skipping EventSub connect", user.getId())
            );
    }

    public void disconnect(UserAccount user) {
        cancelWatchdog(user.getId());
        keepaliveTimeoutSeconds.remove(user.getId());
        WebSocket ws = connections.remove(user.getId());
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bot disabled");
        }
        channelStates.remove(user.getId());
        channelStateWarnedUsers.remove(user.getId());
        streamStateService.clear(user);
    }

    private void openConnection(UserAccount user, OAuthToken token, String wsUrl,
                                long retryDelaySeconds, boolean reconnectSession) {
        httpClient
            .newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new EventSubListener(user, token, retryDelaySeconds, reconnectSession))
            .whenComplete((ws, ex) -> {
                if (ex != null) {
                    log.warn("Failed to open EventSub WebSocket for user {}: {}", user.getId(), ex.getMessage());
                    scheduleReconnect(user, token, retryDelaySeconds);
                } else {
                    // A session_reconnect replaces the previous socket: register the new one
                    // first, then close the old — its onClose sees a foreign socket and no-ops.
                    WebSocket previous = connections.put(user.getId(), ws);
                    if (previous != null && previous != ws) {
                        previous.sendClose(WebSocket.NORMAL_CLOSURE, "replaced by newer session");
                    }
                }
            });
    }

    private void scheduleReconnect(UserAccount user, OAuthToken token, long delaySeconds) {
        long nextDelay = Math.min(delaySeconds * 2, MAX_RETRY_SECONDS);
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
            if (connections.containsKey(user.getId())) return;
            OAuthToken freshToken = oAuthTokenRepository
                .findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .orElse(token);
            // Always a brand-new session on the canonical URL: reconnect URLs are single-use
            openConnection(user, freshToken, WS_URL, nextDelay, false);
        });
    }

    private void armWatchdog(UserAccount user, EventSubListener listener) {
        long timeout = keepaliveTimeoutSeconds.getOrDefault(user.getId(), DEFAULT_KEEPALIVE_SECONDS);
        long deadlineSeconds = Math.max(1L, Math.round(timeout * KEEPALIVE_GRACE));
        ScheduledFuture<?> next = watchdogExecutor.schedule(
            () -> onWatchdogTrigger(user, listener),
            deadlineSeconds,
            TimeUnit.SECONDS
        );
        ScheduledFuture<?> previous = watchdogs.put(user.getId(), next);
        if (previous != null) previous.cancel(false);
    }

    private void cancelWatchdog(UUID userId) {
        ScheduledFuture<?> f = watchdogs.remove(userId);
        if (f != null) f.cancel(false);
    }

    /**
     * Invoked when no message (event or keepalive) has been received within the configured
     * keepalive timeout. Twitch's protocol requires the client to consider the WebSocket
     * dead and reconnect.
     *
     * <p>Design constraints for the implementation:
     * <ul>
     *   <li>Idempotent: a session_reconnect may have already replaced {@code listener.webSocket}
     *       in {@link #connections}. If so, leave the new connection alone.</li>
     *   <li>{@link WebSocket#abort()} closes the TCP socket without triggering {@code onClose},
     *       so explicit cleanup of {@link #connections} is required here.</li>
     *   <li>Reconnect always targets {@link #WS_URL} (the canonical welcome URL) —
     *       a stale reconnect URL is single-use.</li>
     * </ul>
     */
    // Package-private for testing
    void onWatchdogTrigger(UserAccount user, EventSubListener listener) {
        if (!connections.remove(user.getId(), listener.webSocket)) return;
        log.warn("EventSub keepalive timeout for user {}, aborting and reconnecting", user.getId());
        listener.webSocket.abort();
        scheduleReconnect(user, listener.token, listener.retryDelaySeconds);
    }

    // Package-private for testing
    void handleMessage(UserAccount user, OAuthToken token, String message, boolean reconnectSession) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageType = root.path("metadata").path("message_type").asText();
            switch (messageType) {
                case "session_welcome" -> {
                    JsonNode session = root.path("payload").path("session");
                    String sessionId = session.path("id").asText();
                    long timeout = session.path("keepalive_timeout_seconds").asLong(DEFAULT_KEEPALIVE_SECONDS);
                    keepaliveTimeoutSeconds.put(user.getId(), timeout);
                    if (reconnectSession) {
                        // Reconnect via reconnect_url: Twitch carries subscriptions over,
                        // re-subscribing would only yield 409 Conflict noise.
                        log.info("EventSub session_welcome for user {} — reconnected, subscriptions carried over", user.getId());
                    } else {
                        subscribe(user, token, sessionId);
                    }
                }
                case "session_reconnect" -> {
                    String reconnectUrl = root.path("payload").path("session").path("reconnect_url").asText();
                    log.info("EventSub session_reconnect for user {}", user.getId());
                    OAuthToken freshToken = oAuthTokenRepository
                        .findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                        .orElse(token);
                    openConnection(user, freshToken, reconnectUrl, 1L, true);
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
        initStreamState(user, accessToken);
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
            // Sole producer of the "Catapult changed your category" notification: TwitchServiceImpl
            // only records the marker, so one logical change yields exactly one notification.
            Optional<CatapultCategoryChangeStateService.SelfChange> selfSet =
                    categoryChangeStateService.consumeIfMatches(user, newCategoryId);
            if (selfSet.isPresent()) {
                twitchatNotifier.onCategoryChangedByCatapult(user, newCategoryId, newCategoryName,
                        selfSet.get().previousGameId());
            } else {
                twitchatNotifier.onCategoryChangedManually(user, newCategoryId, newCategoryName);
            }
        }
        if (!newCcls.equals(previous.cclIds())) {
            eventPublisher.publishEvent(new ChannelCclChangedEvent(this, user, List.copyOf(newCcls)));
        }
    }

    private void initChannelState(UserAccount user, String accessToken) {
        try {
            String raw = restClient.get()
                .uri(HELIX_CHANNELS_API + "?broadcaster_id=" + user.getTwitchId())
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-ID", twitchClientId)
                .retrieve()
                .body(String.class);
            JsonNode response = objectMapper.readTree(raw);
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
            channelStateWarnedUsers.remove(user.getId());
            log.debug("Initialized channel state for user {}: category={}, ccls={}", user.getId(), categoryId, cclIds);
        } catch (Exception e) {
            if (channelStateWarnedUsers.add(user.getId())) {
                log.warn("Failed to initialize channel state for user {}: {}", user.getId(), e.getMessage(), e);
            }
        }
    }

    private void initStreamState(UserAccount user, String accessToken) {
        try {
            String raw = restClient.get()
                .uri(HELIX_STREAMS_API + "?user_id=" + user.getTwitchId())
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-ID", twitchClientId)
                .retrieve()
                .body(String.class);
            JsonNode response = objectMapper.readTree(raw);
            if (response == null || !response.has("data")) return;
            boolean isLive = !response.path("data").isEmpty();
            streamStateService.setLive(user, isLive);
            log.debug("Initialized stream state for user {}: live={}", user.getId(), isLive);
        } catch (Exception e) {
            log.warn("Failed to initialize stream state for user {}: {}", user.getId(), e.getMessage(), e);
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

    // Package-private for testing
    class EventSubListener implements WebSocket.Listener {
        private final UserAccount user;
        private final OAuthToken token;
        private final long retryDelaySeconds;
        private final boolean reconnectSession;
        private final StringBuilder buffer = new StringBuilder();
        // Written once by the WS callback thread in onOpen, then only read by the watchdog scheduler.
        // No compound updates → volatile is sufficient; AtomicReference would be ceremony.
        @SuppressWarnings("java:S3077")
        private volatile WebSocket webSocket;

        EventSubListener(UserAccount user, OAuthToken token, long retryDelaySeconds, boolean reconnectSession) {
            this.user = user;
            this.token = token;
            this.retryDelaySeconds = retryDelaySeconds;
            this.reconnectSession = reconnectSession;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("EventSub WebSocket opened for user {}", user.getId());
            this.webSocket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(user, token, message, reconnectSession);
                armWatchdog(user, this);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("EventSub WebSocket closed for user {} ({}): {}", user.getId(), statusCode, reason);
            // Only the currently-registered socket may clean up and reconnect: after a
            // session_reconnect this socket may already have been replaced by its successor.
            if (!connections.remove(user.getId(), webSocket)) return null;
            cancelWatchdog(user.getId());
            // Self-initiated closes de-register before closing, so reaching this point
            // means the server closed us — reconnect regardless of the status code.
            scheduleReconnect(user, token, retryDelaySeconds);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("EventSub WebSocket error for user {}: {}", user.getId(), error.getMessage());
            if (!connections.remove(user.getId(), webSocket)) return;
            cancelWatchdog(user.getId());
            scheduleReconnect(user, token, retryDelaySeconds);
        }
    }
}
