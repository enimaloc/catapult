package fr.enimaloc.catapult.simulation.twitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.service.gueststar.GuestStarConsent;
import fr.enimaloc.catapult.service.gueststar.GuestStarRoster;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Guest Star under the simulation: the consent and the session as the account's simulated data has them. */
class SimulatedGuestStarTest {

    @TempDir
    Path dir;

    SimulationFixture fx;
    SimulatedGuestStarConsent consent;
    SimulatedGuestStarSessionClient sessions;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
        consent = new SimulatedGuestStarConsent(fx.tokens, fx.registry);
        sessions = new SimulatedGuestStarSessionClient(RestClient.create(), new ObjectMapper(), fx.tokens,
                mock(TwitchTokenService.class), fx.runner, fx.registry);
    }

    @Test
    void aFictitiousAccountGivesItsConsentInItsData() {
        assertThat(consent.state(fx.fictitious)).isEqualTo(GuestStarConsent.State.NEEDS_CONSENT);

        fx.registry.update(fx.fictitious.getId(), data -> data.withGuestStarConsent(true));

        assertThat(consent.hasScope(fx.fictitious)).isTrue();
        assertThat(consent.state(fx.fictitious)).isEqualTo(GuestStarConsent.State.READY);
    }

    @Test
    void aRealTokenWithTheScopeStillCounts() {
        fx.realToken.setGrantedScopes("user:read:email " + GuestStarConsent.SCOPE);
        assertThat(consent.hasScope(fx.real)).isTrue();
    }

    @Test
    void theSessionAnswersTheSimulatedGuests() {
        fx.registry.update(fx.fictitious.getId(), data -> data.withGuestStars(
                List.of(new SimulatedData.SimulatedGuest("1", "sim-carol", "Carol"))));

        assertThat(sessions.liveGuests(fx.fictitious))
                .containsExactly(new GuestStarRoster.Guest("1", "sim-carol", "carol", "Carol"));
        assertThat(fx.journaledKeys()).containsExactly(SimulatedCalls.TWITCH_HELIX_GUEST_STAR_SESSION.id());
    }

    @Test
    void aSimulatedFailureAnswersNoGuest() {
        fx.registry.update(fx.fictitious.getId(), data -> data.withGuestStars(
                List.of(new SimulatedData.SimulatedGuest("1", "sim-carol", "Carol"))));
        fx.set(fx.fictitious, SimulatedCalls.TWITCH_HELIX_GUEST_STAR_SESSION, CallSetting.error(503));

        assertThat(sessions.liveGuests(fx.fictitious)).isEmpty();
    }
}
