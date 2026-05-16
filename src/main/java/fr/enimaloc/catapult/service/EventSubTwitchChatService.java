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
    private final Set<UUID> intentionallyDisconnected = ConcurrentHashMap.newKeySet();
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
        intentionallyDisconnected.remove(user.getId());
        disconnect(user);
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> openConnection(user, token, WS_URL, 1L),
                () -> log.debug("[EventSub Chat] No token for user {}", user.getId())
            );
    }

    @Override
    public void disconnect(UserAccount user) {
        intentionallyDisconnected.add(user.getId());
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
                } else if (connections.putIfAbsent(user.getId(), ws) != null) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "duplicate connection");
                }
            });
    }

    private void scheduleReconnect(UserAccount user, OAuthToken token, String wsUrl, long delaySeconds) {
        long nextDelay = Math.min(delaySeconds * 2, MAX_RETRY_SECONDS);
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
            if (connections.containsKey(user.getId())) return;
            if (intentionallyDisconnected.contains(user.getId())) return;
            OAuthToken freshToken = oAuthTokenRepository
                .findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .orElse(token);
            openConnection(user, freshToken, wsUrl, nextDelay);
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
            ChatCommandEvent.SenderRole role = extractRole(event);

            eventPublisher.publishEvent(new ChatCommandEvent(this, user, command, args, role));

        } else if ("channel.channel_points_custom_reward_redemption.add".equals(subscriptionType)) {
            String rewardTitle = event.path("reward").path("title").asText();
            eventPublisher.publishEvent(new ChatCommandEvent(this, user, "reward:" + rewardTitle,
                List.of(), ChatCommandEvent.SenderRole.EVERYONE));
            log.info("[EventSub Chat] Reward redeemed: '{}' for user {}", rewardTitle, user.getId());
        }
    }

    private ChatCommandEvent.SenderRole extractRole(JsonNode event) {
        String chatterType = event.path("chatter_type").asText("");
        return switch (chatterType) {
            case "broadcaster" -> ChatCommandEvent.SenderRole.BROADCASTER;
            case "mod"         -> ChatCommandEvent.SenderRole.MODERATOR;
            default            -> ChatCommandEvent.SenderRole.EVERYONE;
        };
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
        if (durationSeconds <= 0) return;
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
                .uri(HELIX_USERS_URL + "?login=" + java.net.URLEncoder.encode(login, java.nio.charset.StandardCharsets.UTF_8))
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
            if (!intentionallyDisconnected.contains(user.getId())) {
                scheduleReconnect(user, token, wsUrl, retryDelaySeconds);
            }
        }
    }
}
