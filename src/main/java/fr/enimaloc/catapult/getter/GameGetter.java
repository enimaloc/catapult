package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.UserAccount;

import java.util.Optional;

/**
 * Interface commune pour tous les game getters.
 * Chaque implémentation interroge un provider différent (Steam, Discord, ...).
 */
public interface GameGetter {

    Optional<DetectedGame> getCurrentGame(UserAccount user);
}
