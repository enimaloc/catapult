package fr.enimaloc.catapult.service.gueststar;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuestStarConsentTest {

    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final GuestStarConsent consent = new GuestStarConsent(tokens);
    private final UserAccount user = new UserAccount();

    private void scopes(String granted) {
        user.setId(UUID.randomUUID());
        OAuthToken token = new OAuthToken();
        token.setGrantedScopes(granted);
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
    }

    @Test
    void readyWithTheScope() {
        scopes("user:read:email channel:read:guest_star");
        assertThat(consent.state(user)).isEqualTo(GuestStarConsent.State.READY);
        assertThat(consent.hasScope(user)).isTrue();
    }

    @Test
    void needsConsentWithoutTheScope() {
        scopes("user:read:email channel:read:guest_starx");
        assertThat(consent.state(user)).isEqualTo(GuestStarConsent.State.NEEDS_CONSENT);
        scopes(null);
        assertThat(consent.hasScope(user)).isFalse();
    }

    @Test
    void needsConsentWithoutToken() {
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());
        assertThat(consent.state(user)).isEqualTo(GuestStarConsent.State.NEEDS_CONSENT);
    }
}
