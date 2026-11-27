package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.gueststar.GuestStarConsent;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * {@link GuestStarConsent} under the simulation: a fictitious account has no Twitch token to carry the scope, its
 * consent is given from the panel, in its simulated data. A real account's token still counts as it does.
 */
@Primary
@Service
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedGuestStarConsent extends GuestStarConsent {

    private final SimulationRegistry registry;

    public SimulatedGuestStarConsent(OAuthTokenRepository tokens, SimulationRegistry registry) {
        super(tokens);
        this.registry = registry;
    }

    @Override
    public boolean hasScope(UserAccount user) {
        return registry.data(user.getId()).guestStarConsent() || super.hasScope(user);
    }
}
