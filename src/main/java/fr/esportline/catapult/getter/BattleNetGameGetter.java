package fr.esportline.catapult.getter;

import fr.esportline.catapult.domain.UserAccount;
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
public class BattleNetGameGetter implements GameGetter {

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return Optional.empty();
    }
}
