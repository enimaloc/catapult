package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.IrcTwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchStreamInfo;
import fr.enimaloc.catapult.service.twitch.TwitchUserProfile;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link TwitchChatService} with each call in the mode of the channel it acts on, {@code REAL} handing it to the
 * transport {@code app.chat.provider} selects (EventSub or IRC). Both real transports log and swallow
 * every failure, so a simulated one does too: an action does nothing, a lookup answers empty. A simulated success
 * is only journaled. {@link #disconnect} makes no call and always reaches the transport, closing whatever a real
 * {@link #connect} opened.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedTwitchChatService implements TwitchChatService {

    /** When a simulated chatter followed or created their account: some time ago, the same every time. */
    private static final Instant SIMULATED_PAST = Instant.parse("2024-01-01T00:00:00Z");
    private static final int REQUEST_PREVIEW = 120;

    private final ObjectProvider<EventSubTwitchChatService> eventSub;
    private final ObjectProvider<IrcTwitchChatService> irc;
    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;

    @Override
    public void connect(UserAccount user) {
        act(user, SimulatedCalls.TWITCH_CHAT_CONNECT, "open the chat connection", () -> real().connect(user));
    }

    @Override
    public void disconnect(UserAccount user) {
        TwitchChatService transport = transport();
        if (transport != null) {
            transport.disconnect(user);
        }
    }

    @Override
    public void sendMessage(UserAccount user, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        act(user, SimulatedCalls.TWITCH_CHAT_SEND_MESSAGE, "POST /chat/messages " + preview(message),
                () -> real().sendMessage(user, message));
    }

    @Override
    public void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        if (durationSeconds <= 0) {
            return;
        }
        act(user, SimulatedCalls.TWITCH_CHAT_TIMEOUT, "POST /moderation/bans " + targetLogin + " " + durationSeconds
                + "s", () -> real().timeout(user, targetLogin, durationSeconds, reason));
    }

    @Override
    public void ban(UserAccount user, String targetLogin, String reason) {
        act(user, SimulatedCalls.TWITCH_CHAT_BAN, "POST /moderation/bans " + targetLogin,
                () -> real().ban(user, targetLogin, reason));
    }

    @Override
    public void unban(UserAccount user, String targetLogin) {
        act(user, SimulatedCalls.TWITCH_CHAT_UNBAN, "DELETE /moderation/bans " + targetLogin,
                () -> real().unban(user, targetLogin));
    }

    /** Live when the simulated data says so, on the channel's simulated category. */
    @Override
    public Optional<TwitchStreamInfo> getStreamInfo(UserAccount user) {
        return lookup(user, SimulatedCalls.TWITCH_CHAT_STREAM_INFO, "GET /streams",
                () -> real().getStreamInfo(user), () -> {
                    SimulatedData data = registry.data(user.getId());
                    return data.live()
                            ? Optional.of(new TwitchStreamInfo("Simulated stream", data.currentCategoryName(), 42,
                                    Instant.now()))
                            : Optional.empty();
                });
    }

    @Override
    public Optional<TwitchUserProfile> getUserProfile(UserAccount user, String login) {
        return lookup(user, SimulatedCalls.TWITCH_CHAT_USER_PROFILE, "GET /users?login=" + login,
                () -> real().getUserProfile(user, login),
                () -> Optional.of(new TwitchUserProfile(login, SIMULATED_PAST)));
    }

    @Override
    public Optional<Instant> getFollowedAt(UserAccount user, String login) {
        return lookup(user, SimulatedCalls.TWITCH_CHAT_FOLLOWED_AT, "GET /channels/followers " + login,
                () -> real().getFollowedAt(user, login), () -> Optional.of(SIMULATED_PAST));
    }

    @Override
    public Optional<Instant> getFollowedAtById(UserAccount user, String targetTwitchId) {
        return lookup(user, SimulatedCalls.TWITCH_CHAT_FOLLOWED_AT, "GET /channels/followers id=" + targetTwitchId,
                () -> real().getFollowedAtById(user, targetTwitchId), () -> Optional.of(SIMULATED_PAST));
    }

    @Override
    public void shoutout(UserAccount user, String targetLogin) {
        act(user, SimulatedCalls.TWITCH_CHAT_SHOUTOUT, "POST /chat/shoutouts " + targetLogin,
                () -> real().shoutout(user, targetLogin));
    }

    private void act(UserAccount user, CallKey key, String request, Runnable realCall) {
        try {
            runner.runVoid(user.getId(), key, request, realCall, () -> {}, SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("[Simulated Chat] {} failed for user {}: {}", key.call(), user.getId(), e.getMessage());
        }
    }

    private <T> Optional<T> lookup(UserAccount user, CallKey key, String request, Supplier<Optional<T>> realCall,
                                   Supplier<Optional<T>> simulated) {
        try {
            return runner.run(user.getId(), key, request, realCall, simulated, SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("[Simulated Chat] {} failed for user {}: {}", key.call(), user.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static String preview(String message) {
        return message.length() <= REQUEST_PREVIEW ? message : message.substring(0, REQUEST_PREVIEW) + "...";
    }

    /** The transport {@code app.chat.provider} selected, {@code null} when none is. */
    private TwitchChatService transport() {
        TwitchChatService transport = eventSub.getIfAvailable();
        if (transport == null) {
            transport = irc.getIfAvailable();
        }
        return transport;
    }

    private TwitchChatService real() {
        TwitchChatService transport = transport();
        if (transport == null) {
            throw new IllegalStateException("No Twitch chat transport is available (app.chat.provider)");
        }
        return transport;
    }
}
