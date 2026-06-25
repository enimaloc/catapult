package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private final ChannelEventPublisher channelEventPublisher;

    public Optional<DetectedGame> getLastKnownGame(UserAccount user) {
        return Optional.ofNullable(lastKnownGame.get(user.getId()));
    }

    public void updateState(UserAccount user, DetectedGame game) {
        DetectedGame previous = lastKnownGame.put(user.getId(), game);
        if (!sameSource(previous, game)) {
            channelEventPublisher.gameDetected(user.getId(), game);
        }
    }

    public void clearState(UserAccount user) {
        DetectedGame previous = lastKnownGame.remove(user.getId());
        if (previous != null) {
            channelEventPublisher.gameCleared(user.getId());
        }
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
