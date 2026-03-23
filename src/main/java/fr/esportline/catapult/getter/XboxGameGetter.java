package fr.esportline.catapult.getter;

import fr.esportline.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Détecte le jeu en cours via Xbox Live.
 * TODO: implémenter via Microsoft Graph API + Xbox Title Hub
 *   - OAuth2 scopes : XboxLive.signin, XboxLive.offline_access
 *   - Endpoint : https://titlehub.xboxlive.com/users/xuid({xuid})/titles/titleHistory/decoration/detail
 */
@Component
public class XboxGameGetter implements GameGetter {

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return Optional.empty();
    }
}
