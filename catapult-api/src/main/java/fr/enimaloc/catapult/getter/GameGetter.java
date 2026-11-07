package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;

import java.util.Optional;

/**
 * Common interface of all game getters.
 * Each implementation queries a different provider (Steam, Discord, ...).
 */
public interface GameGetter {
    String name();

    Optional<DetectedGame> getCurrentGame(UserAccount user);
}
