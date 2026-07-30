// src/main/java/fr/enimaloc/catapult/service/MockTwitchChatService.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "mock", matchIfMissing = true)
public class MockTwitchChatService implements TwitchChatService {

    private final Map<UUID, String> lastMessageByUser = new ConcurrentHashMap<>();

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
        lastMessageByUser.put(user.getId(), message);
    }

    /** Returns the last chat message captured for the given user, or {@code null} if none. */
    public String lastMessageFor(UUID userId) {
        return lastMessageByUser.get(userId);
    }

    /** Clears all captured chat messages (useful for test isolation). */
    public void clearMessages() {
        lastMessageByUser.clear();
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

    @Override
    public Optional<TwitchStreamInfo> getStreamInfo(UserAccount user) {
        log.info("[Mock Chat] getStreamInfo() for {}", user.getId());
        return Optional.of(new TwitchStreamInfo("Mock Stream Title", "Just Chatting", 42, Instant.now()));
    }

    @Override
    public Optional<TwitchUserProfile> getUserProfile(UserAccount user, String login) {
        log.info("[Mock Chat] getUserProfile() for {}: {}", user.getId(), login);
        return Optional.of(new TwitchUserProfile(login, Instant.EPOCH));
    }

    @Override
    public Optional<Instant> getFollowedAt(UserAccount user, String targetLogin) {
        log.info("[Mock Chat] getFollowedAt() for {}: {}", user.getId(), targetLogin);
        return Optional.of(Instant.EPOCH);
    }

    @Override
    public Optional<Instant> getFollowedAtById(UserAccount user, String targetTwitchId) {
        log.info("[Mock Chat] getFollowedAtById() for {}: {}", user.getId(), targetTwitchId);
        return Optional.of(Instant.EPOCH);
    }

    @Override
    public void shoutout(UserAccount user, String targetLogin) {
        log.info("[Mock Chat] shoutout() for {}: {}", user.getId(), targetLogin);
    }

}
