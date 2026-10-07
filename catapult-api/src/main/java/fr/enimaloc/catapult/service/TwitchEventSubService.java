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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.mock.twitch-eventsub", havingValue = "false", matchIfMissing = true)
public class TwitchEventSubService implements EventSubService {

    private static final String EVENTSUB_API = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String HELIX_CHANNELS_API = "https://api.twitch.tv/helix/channels";
    private static final String HELIX_STREAMS_API = "https://api.twitch.tv/helix/streams";

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

    private final Map<UUID, ChannelState> channelStates = new ConcurrentHashMap<>();
    private final Set<UUID> channelStateWarnedUsers = ConcurrentHashMap.newKeySet();
    private final EventSubConnections connections;

    public TwitchEventSubService(OAuthTokenRepository oAuthTokenRepository,
                                 UserAccountRepository userAccountRepository,
                                 TwitchTokenService twitchTokenService,
                                 StreamStateService streamStateService,
                                 ApplicationEventPublisher eventPublisher,
                                 RestClient restClient,
                                 ObjectMapper objectMapper,
                                 TwitchatNotifier twitchatNotifier,
                                 CatapultCategoryChangeStateService categoryChangeStateService) {
        this.oAuthTokenRepository = oAuthTokenRepository;
        this.userAccountRepository = userAccountRepository;
        this.twitchTokenService = twitchTokenService;
        this.streamStateService = streamStateService;
        this.eventPublisher = eventPublisher;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.twitchatNotifier = twitchatNotifier;
        this.categoryChangeStateService = categoryChangeStateService;
        this.connections = new EventSubConnections(
            "[EventSub]", "twitch-eventsub-watchdog", oAuthTokenRepository, this::handleMessage);
    }

    private record ChannelState(String categoryId, String categoryName, Set<String> cclIds) {}

    @PostConstruct
    public void init() {
        userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)
            .forEach(this::connect);
    }

    @PreDestroy
    public void shutdown() {
        connections.shutdown("application shutdown");
    }

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        connect(event.getUser());
    }

    public void connect(UserAccount user) {
        disconnect(user);
        connections.allowReconnect(user);
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> connections.open(user, token),
                () -> log.debug("No Twitch token for user {} — skipping EventSub connect", user.getId())
            );
    }

    public void disconnect(UserAccount user) {
        // Also cancels a reconnect already scheduled for a dropped socket
        connections.forbidReconnect(user);
        connections.close(user, "bot disabled");
        channelStates.remove(user.getId());
        channelStateWarnedUsers.remove(user.getId());
        streamStateService.clear(user);
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
                    long timeout = session.path("keepalive_timeout_seconds").asLong(EventSubConnections.DEFAULT_KEEPALIVE_SECONDS);
                    connections.onWelcome(user, timeout);
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
                    connections.followReconnect(user, token, reconnectUrl);
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
}
