package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.getter.DetectedGame;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintient en mémoire le dernier jeu connu par utilisateur actif.
 * Permet d'éviter des appels Twitch inutiles si le jeu n'a pas changé.
 */
@Service
public class GameStateService {

    private final Map<UUID, DetectedGame> lastKnownGame = new ConcurrentHashMap<>();

    public Optional<DetectedGame> getLastKnownGame(UserAccount user) {
        return Optional.ofNullable(lastKnownGame.get(user.getId()));
    }

    public void updateState(UserAccount user, DetectedGame game) {
        lastKnownGame.put(user.getId(), game);
    }

    public void clearState(UserAccount user) {
        lastKnownGame.remove(user.getId());
    }

    public boolean hasChanged(UserAccount user, DetectedGame newGame) {
        DetectedGame known = lastKnownGame.get(user.getId());
        if (known == null) return true;
        return !known.getSourceId().equals(newGame.getSourceId())
            || !known.getSourceType().equals(newGame.getSourceType());
    }
}
