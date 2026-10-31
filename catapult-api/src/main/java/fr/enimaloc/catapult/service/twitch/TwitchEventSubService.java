package fr.enimaloc.catapult.service.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.event.ChannelCclChangedEvent;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData;
import fr.enimaloc.catapult.simulation.SimulationGate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
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
    private final TwitchatEventDispatcher twitchatEventDispatcher;
    private final CatapultCategoryChangeStateService categoryChangeStateService;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final Map<UUID, ChannelState> channelStates = new ConcurrentHashMap<>();
    private final Set<UUID> channelStateWarnedUsers = ConcurrentHashMap.newKeySet();
    private final EventSubConnections connections;

    private final DebugRecording debug;
    private final ObjectProvider<SimulationGate> simulationGate;

    public TwitchEventSubService(OAuthTokenRepository oAuthTokenRepository,
                                 UserAccountRepository userAccountRepository,
                                 TwitchTokenService twitchTokenService,
                                 StreamStateService streamStateService,
                                 ApplicationEventPublisher eventPublisher,
                                 RestClient restClient,
                                 ObjectMapper objectMapper,
                                 TwitchatEventDispatcher twitchatEventDispatcher,
                                 CatapultCategoryChangeStateService categoryChangeStateService,
                                 DebugRecording debug,
                                 ObjectProvider<SimulationGate> simulationGate) {
        this.oAuthTokenRepository = oAuthTokenRepository;
        this.userAccountRepository = userAccountRepository;
        this.twitchTokenService = twitchTokenService;
        this.streamStateService = streamStateService;
        this.eventPublisher = eventPublisher;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.twitchatEventDispatcher = twitchatEventDispatcher;
        this.categoryChangeStateService = categoryChangeStateService;
        this.debug = debug;
        this.simulationGate = simulationGate;
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

    /**
     * Under the simulation, the real connection only opens while one of the user's EventSub events is {@code REAL};
     * otherwise the channel's initial state comes from the simulated data and only injected events arrive. Without
     * it, a fictitious account of the mock profile is skipped.
     */
    public void connect(UserAccount user) {
        if (SimulationGate.ignoresFictitious(simulationGate, user)) {
            log.debug("User {} is a fictitious account and the simulation is off — skipping EventSub connect",
                user.getId());
            return;
        }
        disconnect(user);
        if (simulatedEvents(user).size() == SUBSCRIPTIONS.size()) {
            log.debug("Every EventSub event of user {} is simulated — no real connection", user.getId());
            seedSimulatedState(user, true, true);
            return;
        }
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

    /**
     * Hands a notification the simulation made up to the same handling as a real one, past the filter that drops
     * the real events of a simulated type. A {@code channel.update} needs the channel's previous state to tell a
     * change: when there is none yet, it is seeded from the simulated data first.
     *
     * @param subscriptionType the EventSub type ({@code stream.online}, {@code channel.update}…)
     * @param event            the notification's {@code payload.event}
     * @throws IllegalStateException outside the simulation
     */
    public void injectNotification(UserAccount user, String subscriptionType, JsonNode event) {
        SimulationGate gate = simulationGate.getIfAvailable();
        if (gate == null) {
            throw new IllegalStateException("EventSub notifications are only injected under the simulation");
        }
        if ("channel.update".equals(subscriptionType)) {
            seedChannelStateIfAbsent(user);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("metadata")
            .put("message_id", UUID.randomUUID().toString())
            .put("message_type", "notification")
            .put("message_timestamp", Instant.now().toString())
            .put("subscription_type", subscriptionType);
        ObjectNode payload = root.putObject("payload");
        payload.putObject("subscription").put("type", subscriptionType);
        payload.set("event", event);
        handle(user, null, root.toString(), false, true);
    }

    /**
     * Under the simulation, gives the channel a previous state from the simulated data when it has none yet, so the
     * next injected {@code channel.update} is told apart from it. Called before a change the simulated data will
     * already hold by the time its notification is injected. Does nothing outside the simulation.
     */
    public void seedChannelStateIfAbsent(UserAccount user) {
        SimulationGate gate = simulationGate.getIfAvailable();
        if (gate != null) {
            channelStates.computeIfAbsent(user.getId(), id -> channelState(gate.data(id)));
        }
    }

    // Package-private for testing
    void handleMessage(UserAccount user, OAuthToken token, String message, boolean reconnectSession) {
        handle(user, token, message, reconnectSession, false);
    }

    /** {@code token} is only read by the session messages: an injected notification has none. */
    private void handle(UserAccount user, @Nullable OAuthToken token, String message, boolean reconnectSession,
                        boolean injected) {
        DebugContext.runAs(user.getId(), () -> {
            debug.inbound(user.getId(), "twitch", "WS", EventSubConnections.WS_URI, message,
                () -> DebugRecording.eventSubOperation(message));
            dispatch(user, token, message, reconnectSession, injected);
        });
    }

    private void dispatch(UserAccount user, @Nullable OAuthToken token, String message, boolean reconnectSession,
                          boolean injected) {
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
                        connections.onSubscribed(user);
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
                    if (!injected && simulated(user, subscriptionType)) {
                        log.debug("EventSub {} for user {} dropped — simulated", subscriptionType, user.getId());
                        return;
                    }
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
                        case "channel.raid" -> handleRaid(user, root.path("payload").path("event"));
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

    /**
     * One EventSub subscription on the user's channel: {@code conditionKey} names the condition holding their id,
     * {@code simulationKey} the call key setting its mode under the simulation.
     */
    private record Subscription(String type, String version, String conditionKey, CallKey simulationKey) {}

    private static final List<Subscription> SUBSCRIPTIONS = List.of(
        new Subscription("stream.online", "1", "broadcaster_user_id", SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE),
        new Subscription("stream.offline", "1", "broadcaster_user_id", SimulatedCalls.TWITCH_EVENTSUB_STREAM_OFFLINE),
        new Subscription("channel.update", "2", "broadcaster_user_id", SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE),
        // The streamer's outgoing raids only (no scope needed)
        new Subscription("channel.raid", "1", "from_broadcaster_user_id", SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_RAID)
    );

    /** Whether the simulation is on and keeps the real events of {@code subscriptionType} out for the user. */
    private boolean simulated(UserAccount user, String subscriptionType) {
        return SUBSCRIPTIONS.stream()
            .filter(sub -> sub.type().equals(subscriptionType))
            .anyMatch(sub -> SimulationGate.blocks(simulationGate, user.getId(), sub.simulationKey()));
    }

    /** The types the simulation keeps out for the user: none without it. */
    private Set<String> simulatedEvents(UserAccount user) {
        Set<String> types = new HashSet<>();
        for (Subscription sub : SUBSCRIPTIONS) {
            if (SimulationGate.blocks(simulationGate, user.getId(), sub.simulationKey())) {
                types.add(sub.type());
            }
        }
        return types;
    }

    /**
     * The initial state of what the simulation keeps out, taken from the simulated data rather than Helix: the
     * category (when {@code channel} is) and the stream's liveness (when {@code stream} is). A simulated live
     * channel is set live as the Helix stream lookup would, without a {@link StreamOnlineEvent}.
     */
    private void seedSimulatedState(UserAccount user, boolean channel, boolean stream) {
        SimulationGate gate = simulationGate.getIfAvailable();
        if (gate == null) return;
        SimulatedData data = gate.data(user.getId());
        if (channel) {
            seedChannelState(user, data);
        }
        if (stream && data.live()) {
            streamStateService.setLive(user, true);
        }
    }

    private void seedChannelState(UserAccount user, SimulatedData data) {
        channelStates.put(user.getId(), channelState(data));
    }

    private static ChannelState channelState(SimulatedData data) {
        return new ChannelState(data.currentCategoryId(), data.currentCategoryName(), new HashSet<>(data.ccls()));
    }

    private void subscribe(UserAccount user, OAuthToken token, String sessionId) {
        Set<String> simulated = simulatedEvents(user);
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        for (Subscription sub : SUBSCRIPTIONS) {
            if (simulated.contains(sub.type())) {
                continue;
            }
            String eventType = sub.type();
            try {
                postSubscription(accessToken, sub, user.getTwitchId(), sessionId);
                log.debug("Subscribed to {} for user {}", eventType, user.getId());
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("Twitch refused {} for user {}, opening a new session: {}", eventType, user.getId(), e.getMessage());
                connections.reopen(user, token);
                return;
            } catch (HttpClientErrorException.Unauthorized e) {
                log.debug("Token expired for user {}, refreshing", user.getId());
                String refreshed = twitchTokenService.refreshAccessToken(token, user);
                if (refreshed != null) {
                    accessToken = refreshed;
                    try {
                        postSubscription(refreshed, sub, user.getTwitchId(), sessionId);
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
        connections.onSubscribed(user);
        boolean channelSimulated = simulated.contains("channel.update");
        boolean streamSimulated = simulated.contains("stream.online") && simulated.contains("stream.offline");
        if (!channelSimulated) {
            initChannelState(user, accessToken);
        }
        if (!streamSimulated) {
            initStreamState(user, accessToken);
        }
        seedSimulatedState(user, channelSimulated, streamSimulated);
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
                twitchatEventDispatcher.onCategoryChangedByCatapult(user, newCategoryId, newCategoryName,
                        selfSet.get().previousGameId(), previous.categoryName());
            } else {
                twitchatEventDispatcher.onCategoryChangedManually(user, newCategoryId, newCategoryName, previous.categoryName());
            }
        }
        if (!newCcls.equals(previous.cclIds())) {
            eventPublisher.publishEvent(new ChannelCclChangedEvent(this, user, List.copyOf(newCcls)));
        }
    }

    /** An outgoing raid (the subscription only asks for those; an incoming one is ignored all the same). */
    private void handleRaid(UserAccount user, JsonNode event) {
        if (!event.path("from_broadcaster_user_id").asText().equals(user.getTwitchId())) return;
        ChannelState state = channelStates.get(user.getId());
        eventPublisher.publishEvent(new ChannelRaidedOutEvent(this, user, state == null ? null : state.categoryId()));
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

    private void postSubscription(String accessToken, Subscription sub, String twitchId, String sessionId) {
        restClient.post()
            .uri(EVENTSUB_API)
            .header("Authorization", "Bearer " + accessToken)
            .header("Client-ID", twitchClientId)
            .header("Content-Type", "application/json")
            .body(Map.of(
                "type", sub.type(),
                "version", sub.version(),
                "condition", Map.of(sub.conditionKey(), twitchId),
                "transport", Map.of("method", "websocket", "session_id", sessionId)
            ))
            .retrieve()
            .toBodilessEntity();
    }
}
