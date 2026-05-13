package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Détecte le jeu en cours via Battle.net (Blizzard).
 * TODO: implémenter via Blizzard OAuth2 API
 *   - OAuth2 scopes : openid
 *   - Endpoint : https://oauth.battle.net/userinfo (identité uniquement)
 *   - Détection du jeu actif nécessite les APIs par jeu (WoW, Overwatch, etc.)
 */
@Component
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetGameGetter implements GameGetter {

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return Optional.empty();
    }
}
