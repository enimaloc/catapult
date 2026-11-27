package fr.enimaloc.catapult.simulation.twitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.gueststar.GuestStarRoster;
import fr.enimaloc.catapult.service.gueststar.GuestStarSessionClient;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * {@link GuestStarSessionClient} under the simulation: the session read in the account's
 * {@code twitch.helix.guest-star-session} mode, a simulated one answering the guests of its simulated data. A
 * failure answers no guest, as the real read does.
 */
@Slf4j
@Primary
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedGuestStarSessionClient extends GuestStarSessionClient {

    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;

    public SimulatedGuestStarSessionClient(RestClient restClient, ObjectMapper objectMapper,
                                           OAuthTokenRepository tokens, TwitchTokenService tokenService,
                                           SimulatedCallRunner runner, SimulationRegistry registry) {
        super(restClient, objectMapper, tokens, tokenService);
        this.runner = runner;
        this.registry = registry;
    }

    @Override
    public List<GuestStarRoster.Guest> liveGuests(UserAccount user) {
        try {
            return runner.run(user.getId(), SimulatedCalls.TWITCH_HELIX_GUEST_STAR_SESSION, "session",
                    () -> super.liveGuests(user),
                    () -> registry.data(user.getId()).guestStars().stream()
                            .map(guest -> new GuestStarRoster.Guest(guest.slotId(), guest.userId(),
                                    guest.displayName().toLowerCase(java.util.Locale.ROOT), guest.displayName()))
                            .toList(),
                    SimulatedCallRunner::httpError);
        } catch (RuntimeException e) {
            log.warn("Could not read the Guest Star session of user {}: {}", user.getId(), e.getMessage());
            return List.of();
        }
    }
}
