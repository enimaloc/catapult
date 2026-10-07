package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.ChatMessageSplitter;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "eventsub")
public class EventSubTwitchChatService implements TwitchChatService {

    private static final String EVENTSUB_API = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String HELIX_CHAT_URL = "https://api.twitch.tv/helix/chat/messages";

    public static final String PAYLOAD = "payload";
    public static final String CLIENT_ID = "Client-Id";
    public static final String AUTHORIZATION = "Authorization";
    public static final String AUTHORIZATION_BEARER = "Bearer ";

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final TwitchTokenService twitchTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final TwitchHelixChannelClient helix;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final SystemTwitchAccountService systemTwitchAccountService;
    private final MeterRegistry meterRegistry;
    private final ExternalApiObservations apiObservations;
    private final TwitchChatRateLimiter chatRateLimiter;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final EventSubConnections connections;

    public EventSubTwitchChatService(OAuthTokenRepository oAuthTokenRepository,
                                     UserAccountRepository userAccountRepository,
                                     TwitchTokenService twitchTokenService,
                                     ApplicationEventPublisher eventPublisher,
                                     TwitchHelixChannelClient helix,
                                     RestClient restClient,
                                     ObjectMapper objectMapper,
                                     SystemTwitchAccountService systemTwitchAccountService,
                                     MeterRegistry meterRegistry,
                                     ExternalApiObservations apiObservations,
                                     TwitchChatRateLimiter chatRateLimiter) {
        this.oAuthTokenRepository = oAuthTokenRepository;
        this.userAccountRepository = userAccountRepository;
        this.twitchTokenService = twitchTokenService;
        this.eventPublisher = eventPublisher;
        this.helix = helix;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.systemTwitchAccountService = systemTwitchAccountService;
        this.meterRegistry = meterRegistry;
        this.apiObservations = apiObservations;
        this.chatRateLimiter = chatRateLimiter;
        this.connections = new EventSubConnections(
            "[EventSub Chat]", "twitch-chat-watchdog", oAuthTokenRepository, this::handleMessage);
    }

    public int connectionCount() {
        return connections.size();
    }

    @PostConstruct
    public void init() {
        // Pré-enregistre le compteur pour qu'il soit exporté à 0 avant le premier message
        meterRegistry.counter("catapult.chat.messages", "transport", "eventsub");
        var users = userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE);
        log.info("[EventSub Chat] Bootstrapping connections for {} active bot-enabled user(s)", users.size());
        users.forEach(this::connect);
    }

    @PreDestroy
    public void shutdown() {
        connections.shutdown("shutdown");
    }

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        connect(event.getUser());
    }

    @Override
    public void connect(UserAccount user) {
        connections.allowReconnect(user);
        connections.close(user, "reconnecting");
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> connections.open(user, token),
                () -> log.warn("[EventSub Chat] No Twitch token for user {} ({}) — chat events disabled",
                        user.getId(), user.getTwitchUsername())
            );
    }

    @Override
    public void disconnect(UserAccount user) {
        connections.forbidReconnect(user);
        connections.close(user, "bot disabled");
    }

    void handleMessage(UserAccount user, OAuthToken token, String message, boolean reconnectSession) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageType = root.path("metadata").path("message_type").asText();
            switch (messageType) {
                case "session_welcome" -> {
                    JsonNode session = root.path(PAYLOAD).path("session");
                    String sessionId = session.path("id").asText();
                    long timeout = session.path("keepalive_timeout_seconds").asLong(EventSubConnections.DEFAULT_KEEPALIVE_SECONDS);
                    connections.onWelcome(user, timeout);
                    if (reconnectSession) {
                        // Reconnect via reconnect_url: Twitch carries subscriptions over,
                        // re-subscribing would only yield 409 Conflict noise.
                        log.info("[EventSub Chat] session_welcome for user {} ({}) — reconnected, subscriptions carried over (sessionId={}, keepalive={}s)",
                                user.getId(), user.getTwitchUsername(), sessionId, timeout);
                    } else {
                        log.info("[EventSub Chat] session_welcome for user {} ({}) — subscribing (sessionId={}, keepalive={}s)",
                                user.getId(), user.getTwitchUsername(), sessionId, timeout);
                        subscribe(user, token, sessionId);
                    }
                }
                case "session_reconnect" -> {
                    String reconnectUrl = root.path(PAYLOAD).path("session").path("reconnect_url").asText();
                    log.info("[EventSub Chat] session_reconnect for user {}", user.getId());
                    connections.followReconnect(user, token, reconnectUrl);
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
        JsonNode event = root.path(PAYLOAD).path("event");

        if ("channel.chat.message".equals(subscriptionType)) {
            String text = event.path("message").path("text").asText();
            meterRegistry.counter("catapult.chat.messages", "transport", "eventsub").increment();
            if (!text.startsWith("!")) return;

            String[] parts = text.substring(1).split(" ", 2);
            String command = "!" + parts[0];
            List<String> args = parts.length > 1 ? List.of(parts[1].split(" ")) : List.of();
            ChatCommandEvent.SenderRole role = extractRole(event);
            String senderTwitchId = event.path("chatter_user_id").asText(null);

            log.info("[EventSub Chat] {} in #{}: {} (sender role={})",
                    command, user.getTwitchUsername(), text, role);
            eventPublisher.publishEvent(new ChatCommandEvent(this, user, command, args, role, senderTwitchId));

        } else if ("channel.channel_points_custom_reward_redemption.add".equals(subscriptionType)) {
            String rewardTitle = event.path("reward").path("title").asText();
            eventPublisher.publishEvent(new ChatCommandEvent(this, user, "reward:" + rewardTitle,
                List.of(), ChatCommandEvent.SenderRole.VIEWERS));
            log.info("[EventSub Chat] Reward redeemed: '{}' for user {}", rewardTitle, user.getId());
        }
    }

    /**
     * Checks for the highest-priority badge across the WHOLE collection rather than returning on
     * the first match found while iterating — Twitch doesn't guarantee badge array order, so a
     * subscriber badge listed before a broadcaster/mod one must not shadow it. FOLLOWERS is never
     * returned here — see {@link IrcTwitchChatService#extractRole} for why.
     */
    private ChatCommandEvent.SenderRole extractRole(JsonNode event) {
        Set<String> setIds = new HashSet<>();
        for (JsonNode badge : event.withArray("badges")) {
            setIds.add(badge.path("set_id").asText(""));
        }
        if (setIds.contains("broadcaster")) return ChatCommandEvent.SenderRole.BROADCASTER;
        if (setIds.contains("mod")) return ChatCommandEvent.SenderRole.MODERATOR;
        if (setIds.contains("vip")) return ChatCommandEvent.SenderRole.VIP;
        if (setIds.contains("subscriber")) return ChatCommandEvent.SenderRole.SUBS;
        return ChatCommandEvent.SenderRole.VIEWERS;
    }

    private void subscribe(UserAccount user, OAuthToken token, String sessionId) {
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        subscribeEvent(user, token, accessToken, sessionId, "channel.chat.message",
            Map.of("broadcaster_user_id", user.getTwitchId(), "user_id", user.getTwitchId()));
        subscribeEvent(user, token, accessToken, sessionId,
            "channel.channel_points_custom_reward_redemption.add",
            Map.of("broadcaster_user_id", user.getTwitchId()));
    }

    private void subscribeEvent(UserAccount user, OAuthToken token, String accessToken, String sessionId,
                                String type, Map<String, String> condition) {
        try {
            postSubscription(accessToken, sessionId, type, condition);
            log.info("[EventSub Chat] Subscribed {} for user {} ({})",
                    type, user.getId(), user.getTwitchUsername());
        } catch (HttpClientErrorException.Unauthorized e) {
            // Token revoked or expired despite expires_at: force a refresh and retry once
            String refreshed = twitchTokenService.refreshAccessToken(token, user);
            if (refreshed == null) {
                log.warn("[EventSub Chat] Failed to subscribe to {} for user {}: token refresh failed",
                    type, user.getId());
                return;
            }
            try {
                postSubscription(refreshed, sessionId, type, condition);
                log.info("[EventSub Chat] Subscribed {} for user {} ({}) after token refresh",
                        type, user.getId(), user.getTwitchUsername());
            } catch (Exception retryEx) {
                log.warn("[EventSub Chat] Failed to subscribe to {} for user {} after refresh: {}",
                    type, user.getId(), retryEx.getMessage());
            }
        } catch (Exception e) {
            log.warn("[EventSub Chat] Failed to subscribe to {} for user {}: {}",
                type, user.getId(), e.getMessage());
        }
    }

    private void postSubscription(String accessToken, String sessionId,
                                  String type, Map<String, String> condition) {
        apiObservations.observeRun("eventsub", "subscribe", () ->
            restClient.post()
                .uri(EVENTSUB_API)
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                .header(CLIENT_ID, twitchClientId)
                .body(Map.of("type", type, "version", "1", "condition", condition,
                    "transport", Map.of("method", "websocket", "session_id", sessionId)))
                .retrieve()
                .toBodilessEntity()
        );
    }

    @Override
    public void sendMessage(UserAccount user, String message) {
        // Twitch caps a single chat message at 500 chars. Anything longer is
        // sliced into [i/N]-prefixed parts so the streamer's full response
        // reaches the channel instead of being silently truncated by Helix.
        List<String> parts = ChatMessageSplitter.split(message);
        if (parts.isEmpty()) return;

        // 1. Si un compte système est configuré (i.e. promu via /admin/members
        //    et donc présent dans oauth_token), on envoie via lui. Le bot peut
        //    poster même sans être mod du canal — Twitch ne bloque pas
        //    l'envoi, il applique simplement un rate-limit plus strict pour
        //    les non-mods. La décision bot-ou-streamer est prise sur le 1er
        //    part : si le bot l'envoie OK, on garde le bot pour la suite
        //    (ordre garanti par les appels synchrones via RestClient).
        String botAccess = systemTwitchAccountService.getAccessToken();
        String botTwitchId = systemTwitchAccountService.getSystemTwitchId();
        if (botAccess != null && botTwitchId != null
            && trySend(user, parts.get(0), botAccess, botTwitchId, "bot")) {
            for (int i = 1; i < parts.size(); i++) {
                trySend(user, parts.get(i), botAccess, botTwitchId, "bot");
            }
            return;
        }

        // 2. Fallback : pas de compte système OU l'envoi via bot a échoué.
        //    On utilise le token Twitch du streamer pour tous les parts.
        Optional<OAuthToken> streamerToken = oAuthTokenRepository
            .findByUserAndProvider(user, OAuthToken.Provider.TWITCH);
        if (streamerToken.isEmpty()) {
            log.warn("[EventSub Chat] sendMessage: no system bot AND no streamer token for user {}",
                user.getId());
            return;
        }
        String streamerAccess = twitchTokenService.resolveAccessToken(streamerToken.get(), user);
        for (String part : parts) {
            trySend(user, part, streamerAccess, user.getTwitchId(), "streamer");
        }
    }

    private boolean trySend(UserAccount user, String message, String accessToken,
                            String senderId, String senderLabel) {
        if (!chatRateLimiter.acquire(senderId)) {
            meterRegistry.counter("catapult.chat.commands.send",
                "sender", senderLabel, "outcome", "rate_limited").increment();
            log.warn("[EventSub Chat] sendMessage skipped ({}) for user {}: rate limiter is pausing sender {}",
                senderLabel, user.getId(), senderId);
            return false;
        }
        try {
            restClient.post()
                .uri(HELIX_CHAT_URL)
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                .header(CLIENT_ID, twitchClientId)
                .body(Map.of("broadcaster_id", user.getTwitchId(),
                    "sender_id", senderId, "message", message))
                .retrieve()
                .toBodilessEntity();
            meterRegistry.counter("catapult.chat.commands.send",
                "sender", senderLabel, "outcome", "success").increment();
            return true;
        } catch (HttpClientErrorException.TooManyRequests e) {
            chatRateLimiter.onRateLimitResponse(senderId, retryAfterSeconds(e));
            meterRegistry.counter("catapult.chat.commands.send",
                "sender", senderLabel, "outcome", "failed").increment();
            log.warn("[EventSub Chat] sendMessage failed ({}) for user {}: {}",
                senderLabel, user.getId(), e.getMessage());
            return false;
        } catch (Exception e) {
            meterRegistry.counter("catapult.chat.commands.send",
                "sender", senderLabel, "outcome", "failed").increment();
            log.warn("[EventSub Chat] sendMessage failed ({}) for user {}: {}",
                senderLabel, user.getId(), e.getMessage());
            return false;
        }
    }

    // Twitch reports the reset deadline as a Unix timestamp in seconds (Ratelimit-Reset),
    // not a delta — fall back to a plain Retry-After delta, then a conservative default
    // if Helix returned neither.
    private static long retryAfterSeconds(HttpClientErrorException.TooManyRequests e) {
        String reset = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Ratelimit-Reset") : null;
        if (reset != null) {
            try {
                return Math.max(1, Long.parseLong(reset) - Instant.now().getEpochSecond());
            } catch (NumberFormatException ignored) {
                // fall through to Retry-After
            }
        }
        String retryAfter = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (retryAfter != null) {
            try {
                return Math.max(1, Long.parseLong(retryAfter));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 30L;
    }

    @Override
    public void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        if (durationSeconds <= 0) return;
        withAccessToken(user, accessToken -> helix.moderate(user, accessToken, targetLogin, durationSeconds, reason));
    }

    @Override
    public void ban(UserAccount user, String targetLogin, String reason) {
        withAccessToken(user, accessToken -> helix.moderate(user, accessToken, targetLogin, 0, reason));
    }

    @Override
    public void unban(UserAccount user, String targetLogin) {
        withAccessToken(user, accessToken -> helix.unban(user, accessToken, targetLogin));
    }

    @Override
    public Optional<TwitchStreamInfo> getStreamInfo(UserAccount user) {
        return mapAccessToken(user, accessToken -> helix.streamInfo(user, accessToken));
    }

    @Override
    public Optional<TwitchUserProfile> getUserProfile(UserAccount user, String login) {
        return mapAccessToken(user, accessToken -> helix.userProfile(accessToken, login));
    }

    @Override
    public Optional<Instant> getFollowedAt(UserAccount user, String targetLogin) {
        return mapAccessToken(user, accessToken -> helix.followedAt(user, accessToken, targetLogin));
    }

    @Override
    public Optional<Instant> getFollowedAtById(UserAccount user, String targetTwitchId) {
        return mapAccessToken(user, accessToken -> helix.followedAtById(user, accessToken, targetTwitchId));
    }

    @Override
    public void shoutout(UserAccount user, String targetLogin) {
        withAccessToken(user, accessToken -> helix.shoutout(user, accessToken, targetLogin));
    }

    /** Runs {@code call} with the streamer's access token; does nothing without a Twitch token. */
    private void withAccessToken(UserAccount user, Consumer<String> call) {
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresent(token -> call.accept(twitchTokenService.resolveAccessToken(token, user)));
    }

    /** {@code call}'s result with the streamer's access token; empty without a Twitch token. */
    private <T> Optional<T> mapAccessToken(UserAccount user, Function<String, Optional<T>> call) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .flatMap(token -> call.apply(twitchTokenService.resolveAccessToken(token, user)));
    }
}
