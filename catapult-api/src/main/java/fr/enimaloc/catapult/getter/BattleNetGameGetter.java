package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Detects the current game via Battle.net (Blizzard).
 * TODO: implement through the Blizzard OAuth2 API
 *   - OAuth2 scopes: openid
 *   - Endpoint: {@code https://oauth.battle.net/userinfo} (identity only)
 *   - Detecting the active game needs the per-game APIs (WoW, Overwatch, etc.)
 */
@Component
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetGameGetter implements GameGetter {
    @Override
    public String name() {
        return "Battle.net";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return Optional.empty();
    }
}
