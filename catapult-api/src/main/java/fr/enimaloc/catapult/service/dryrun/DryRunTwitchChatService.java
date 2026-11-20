package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchStreamInfo;
import fr.enimaloc.catapult.service.twitch.TwitchUserProfile;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link TwitchChatService} that holds back every public chat write (messages, timeouts, bans, unbans, shoutouts) on
 * the channel of a member in dry run: nothing is sent, the write is noted ({@link DryRunGate#note}) and returns as if
 * done. The connection itself, the reads, and every call for a member not in dry run go to {@code delegate}
 * unchanged. Wrapped around each {@link TwitchChatService} bean by {@link DryRunTwitchWrapping}. Until who is in dry
 * run is known ({@link DryRunGate#holdsUntilLoaded}), every member's chat write is held too.
 */
public class DryRunTwitchChatService implements TwitchChatService {

    private final TwitchChatService delegate;
    private final Supplier<DryRunGate> gate;

    public DryRunTwitchChatService(TwitchChatService delegate, Supplier<DryRunGate> gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public void sendMessage(UserAccount user, String message) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "sendMessage")) {
            return;
        }
        if (!dryRun.holdsChat(user)) {
            delegate.sendMessage(user, message);
            return;
        }
        // a blank message is never sent, so not noted either
        if (message != null && !message.isBlank()) {
            dryRun.note(user, "sendMessage", "[dry run] would have sent: " + message);
        }
    }

    @Override
    public void timeout(UserAccount user, String targetLogin, int durationSeconds, String reason) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "timeout")) {
            return;
        }
        if (!dryRun.holdsChat(user)) {
            delegate.timeout(user, targetLogin, durationSeconds, reason);
            return;
        }
        // no duration: the real clients send nothing
        if (durationSeconds > 0) {
            dryRun.note(user, "timeout",
                    "[dry run] would have timed out " + targetLogin + " for " + durationSeconds + "s");
        }
    }

    @Override
    public void ban(UserAccount user, String targetLogin, String reason) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "ban")) {
            return;
        }
        if (!dryRun.holdsChat(user)) {
            delegate.ban(user, targetLogin, reason);
            return;
        }
        dryRun.note(user, "ban", "[dry run] would have banned " + targetLogin);
    }

    @Override
    public void unban(UserAccount user, String targetLogin) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "unban")) {
            return;
        }
        if (!dryRun.holdsChat(user)) {
            delegate.unban(user, targetLogin);
            return;
        }
        dryRun.note(user, "unban", "[dry run] would have unbanned " + targetLogin);
    }

    @Override
    public void shoutout(UserAccount user, String targetLogin) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "shoutout")) {
            return;
        }
        if (!dryRun.holdsChat(user)) {
            delegate.shoutout(user, targetLogin);
            return;
        }
        dryRun.note(user, "shoutout", "[dry run] would have shouted out " + targetLogin);
    }

    @Override
    public void connect(UserAccount user) {
        delegate.connect(user);
    }

    @Override
    public void disconnect(UserAccount user) {
        delegate.disconnect(user);
    }

    @Override
    public boolean isConnected(UserAccount user) {
        return delegate.isConnected(user);
    }

    @Override
    public void disconnectAll(String reason) {
        delegate.disconnectAll(reason);
    }

    @Override
    public Optional<TwitchStreamInfo> getStreamInfo(UserAccount user) {
        return delegate.getStreamInfo(user);
    }

    @Override
    public Optional<String> archiveVideoId(UserAccount user, String streamId) {
        return delegate.archiveVideoId(user, streamId);
    }

    @Override
    public Optional<TwitchUserProfile> getUserProfile(UserAccount user, String login) {
        return delegate.getUserProfile(user, login);
    }

    @Override
    public Optional<Instant> getFollowedAt(UserAccount user, String login) {
        return delegate.getFollowedAt(user, login);
    }

    @Override
    public Optional<Instant> getFollowedAtById(UserAccount user, String targetTwitchId) {
        return delegate.getFollowedAtById(user, targetTwitchId);
    }
}
