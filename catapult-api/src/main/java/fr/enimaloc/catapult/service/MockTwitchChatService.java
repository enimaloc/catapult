// src/main/java/fr/enimaloc/catapult/service/MockTwitchChatService.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
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

}
