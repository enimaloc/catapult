package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;

import java.time.Instant;
import java.util.Optional;

public interface TwitchChatService {
    void connect(UserAccount user);
    void disconnect(UserAccount user);
    void sendMessage(UserAccount user, String message);
    void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason);
    void ban(UserAccount user, String targetLogin, String reason);
    void unban(UserAccount user, String targetLogin);

    /** Empty when the streamer's own channel is currently offline. */
    Optional<TwitchStreamInfo> getStreamInfo(UserAccount user);

    Optional<TwitchUserProfile> getUserProfile(UserAccount user, String login);

    /** Empty when {@code login} doesn't follow the streamer's channel (or lookup failed). */
    Optional<Instant> getFollowedAt(UserAccount user, String login);

    void shoutout(UserAccount user, String targetLogin);
}
