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

    /**
     * Same as {@link #getFollowedAt(UserAccount, String)} but keyed by an already-known Twitch
     * user id instead of a login — used by {@link fr.enimaloc.catapult.chat.CommandRegistry}'s
     * FOLLOWERS permission check, which only has the chat sender's id (from the message's own
     * tags/badges), not their login, and would otherwise need an extra id-resolution round trip.
     */
    Optional<Instant> getFollowedAtById(UserAccount user, String targetTwitchId);

    void shoutout(UserAccount user, String targetLogin);
}
