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
    private final Set<UUID> intentionallyDisconnected = ConcurrentHashMap.newKeySet();
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
        intentionallyDisconnected.remove(user.getId());
        doDisconnect(user);
        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> openConnection(user, token, 1L),
                () -> log.debug("[IRC] No Twitch token for user {} — skipping connect", user.getId())
            );
    }

    @Override
    public void disconnect(UserAccount user) {
        intentionallyDisconnected.add(user.getId());
        doDisconnect(user);
    }

    private void doDisconnect(UserAccount user) {
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
            if (!sockets.containsKey(user.getId()) && !intentionallyDisconnected.contains(user.getId())) {
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
            log.warn("[IRC] Failed to resolve user ID for '{}': {}", login, e.getMessage());
            return null;
        }
    }
}
