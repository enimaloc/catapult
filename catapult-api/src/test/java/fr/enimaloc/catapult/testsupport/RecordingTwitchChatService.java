package fr.enimaloc.catapult.testsupport;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchStreamInfo;
import fr.enimaloc.catapult.service.twitch.TwitchUserProfile;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link TwitchChatService} that reaches no chat: it keeps the last message sent to each channel for a test to read,
 * and does nothing else. {@code @Import} it into a context: being {@code @Primary}, it is what the application gets
 * in place of the {@code app.chat.provider} transport.
 */
@Primary
public class RecordingTwitchChatService implements TwitchChatService {

    private final Map<UUID, String> lastMessageByUser = new ConcurrentHashMap<>();

    /** The last message sent to the channel of {@code userId}, {@code null} when none was. */
    public String lastMessageFor(UUID userId) {
        return lastMessageByUser.get(userId);
    }

    public void clearMessages() {
        lastMessageByUser.clear();
    }

    @Override
    public void sendMessage(UserAccount user, String message) {
        lastMessageByUser.put(user.getId(), message);
    }

    @Override
    public void connect(UserAccount user) {
        // no chat to connect to
    }

    @Override
    public void disconnect(UserAccount user) {
        // no chat to disconnect from
    }

    @Override
    public void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        // no chat to moderate
    }

    @Override
    public void ban(UserAccount user, String targetLogin, String reason) {
        // no chat to moderate
    }

    @Override
    public void unban(UserAccount user, String targetLogin) {
        // no chat to moderate
    }

    @Override
    public Optional<TwitchStreamInfo> getStreamInfo(UserAccount user) {
        return Optional.empty();
    }

    @Override
    public Optional<TwitchUserProfile> getUserProfile(UserAccount user, String login) {
        return Optional.empty();
    }

    @Override
    public Optional<Instant> getFollowedAt(UserAccount user, String login) {
        return Optional.empty();
    }

    @Override
    public Optional<Instant> getFollowedAtById(UserAccount user, String targetTwitchId) {
        return Optional.empty();
    }

    @Override
    public void shoutout(UserAccount user, String targetLogin) {
        // no chat to shout out in
    }
}
