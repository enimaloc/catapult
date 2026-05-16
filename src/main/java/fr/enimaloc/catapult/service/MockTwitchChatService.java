// src/main/java/fr/enimaloc/catapult/service/MockTwitchChatService.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "mock", matchIfMissing = true)
@RequiredArgsConstructor
public class MockTwitchChatService implements TwitchChatService {

    private final ApplicationEventPublisher eventPublisher;

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

    public void simulateMessage(UserAccount user, String senderLogin,
                                ChatCommandEvent.SenderRole role, String text) {
        if (!text.startsWith("!")) return;
        String[] parts = text.substring(1).split(" ", 2);
        String command = "!" + parts[0];
        List<String> args = parts.length > 1 ? List.of(parts[1].split(" ")) : List.of();
        eventPublisher.publishEvent(new ChatCommandEvent(this, user, command, args, role));
        log.info("[Mock Chat] simulateMessage() for {}: '{}' from {} ({})",
            user.getId(), text, senderLogin, role);
    }

    public void simulateReward(UserAccount user, String rewardTitle) {
        String command = "reward:" + rewardTitle;
        eventPublisher.publishEvent(new ChatCommandEvent(this, user, command, List.of(),
            ChatCommandEvent.SenderRole.EVERYONE));
        log.info("[Mock Chat] simulateReward() for {}: '{}'", user.getId(), rewardTitle);
    }
}
