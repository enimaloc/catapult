package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintient en mémoire le dernier jeu connu par utilisateur actif.
 * Permet d'éviter des appels Twitch inutiles si le jeu n'a pas changé.
 */
@Service
@RequiredArgsConstructor
public class GameStateService {

    private final Map<UUID, DetectedGame> lastKnownGame = new ConcurrentHashMap<>();
    /**
     * The game each channel last stopped playing and when: kept past {@link #clearState}, which is
     * what lets an outgoing raid put it back (see {@link RaidRestoreGameService}). In memory, like
     * {@link #lastKnownGame}.
     */
    private final Map<UUID, EndedGame> lastEndedGame = new ConcurrentHashMap<>();

    /** Tells the time (tests swap in a fixed clock). */
    Clock clock = Clock.systemUTC();

    private record EndedGame(DetectedGame game, Instant endedAt) {}

    /** The users whose game state has been observed since this instance started (game or no game). */
    private final Set<UUID> observedSinceStart = ConcurrentHashMap.newKeySet();

    private final ChannelEventPublisher channelEventPublisher;

    public Optional<DetectedGame> getLastKnownGame(UserAccount user) {
        return Optional.ofNullable(lastKnownGame.get(user.getId()));
    }

    /** Every channel's last known game, by owner id (a snapshot). */
    public Map<UUID, DetectedGame> lastKnownGames() {
        return Map.copyOf(lastKnownGame);
    }

    /**
     * Records that {@code user}'s game state has just been observed. Tells whether it is the first time since this
     * instance started: the state kept in memory is lost on a restart, so the state before that first observation is
     * unknown, not "no game".
     */
    public boolean markObserved(UserAccount user) {
        return observedSinceStart.add(user.getId());
    }

    public void updateState(UserAccount user, DetectedGame game) {
        observedSinceStart.add(user.getId());
        DetectedGame previous = lastKnownGame.put(user.getId(), game);
        if (!sameSource(previous, game)) {
            channelEventPublisher.gameDetected(user.getId(), game);
        }
    }

    public void clearState(UserAccount user) {
        observedSinceStart.add(user.getId());
        DetectedGame previous = lastKnownGame.remove(user.getId());
        if (previous != null) {
            lastEndedGame.put(user.getId(), new EndedGame(previous, clock.instant()));
            channelEventPublisher.gameCleared(user.getId());
        }
    }

    /**
     * The game detected for this channel within the last {@code within}: the one still being played,
     * else the last one that stopped being detected no longer than {@code within} ago.
     */
    public Optional<DetectedGame> recentlyDetectedGame(UserAccount user, Duration within) {
        DetectedGame current = lastKnownGame.get(user.getId());
        if (current != null) return Optional.of(current);
        EndedGame ended = lastEndedGame.get(user.getId());
        if (ended == null || ended.endedAt().plus(within).isBefore(clock.instant())) return Optional.empty();
        return Optional.of(ended.game());
    }

    public boolean hasChanged(UserAccount user, DetectedGame newGame) {
        DetectedGame known = lastKnownGame.get(user.getId());
        if (known == null) return true;
        return !known.getSourceId().equals(newGame.getSourceId())
            || !known.getSourceType().equals(newGame.getSourceType());
    }

    private static boolean sameSource(DetectedGame a, DetectedGame b) {
        if (a == null || b == null) return a == b;
        return Objects.equals(a.getSourceId(), b.getSourceId())
                && Objects.equals(a.getSourceType(), b.getSourceType());
    }
}
