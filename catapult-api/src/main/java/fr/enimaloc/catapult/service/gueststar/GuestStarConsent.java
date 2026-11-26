package fr.enimaloc.catapult.service.gueststar;

import fr.enimaloc.catapult.domain.account.OAuthScopes;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


/**
 * Whether the streamer gave Catapult their Guest Star consent: the login {@value #REGISTRATION_ID} asks the Twitch
 * scopes plus {@value #SCOPE}, its token replacing the Twitch one. A later plain Twitch login without it takes it away.
 */
@Service
@RequiredArgsConstructor
public class GuestStarConsent {

    public static final String SCOPE = "channel:read:guest_star";
    public static final String REGISTRATION_ID = "twitch-guest-star";

    /** The consent as the settings show it. */
    public enum State { NEEDS_CONSENT, READY }

    private final OAuthTokenRepository tokens;

    public State state(UserAccount user) {
        return hasScope(user) ? State.READY : State.NEEDS_CONSENT;
    }

    /** Whether the user's Twitch token carries {@value #SCOPE}. */
    public boolean hasScope(UserAccount user) {
        return tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .map(OAuthToken::getGrantedScopes)
                .map(scopes -> OAuthScopes.parse(scopes).contains(SCOPE))
                .orElse(false);
    }
}
