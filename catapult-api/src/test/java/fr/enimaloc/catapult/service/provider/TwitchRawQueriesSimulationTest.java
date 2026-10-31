package fr.enimaloc.catapult.service.provider;

import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The admin games probe under the simulation: only sent with the app token while it is REAL. */
class TwitchRawQueriesSimulationTest {

    TwitchTokenService tokens = mock(TwitchTokenService.class);
    RestClient restClient = mock(RestClient.class);
    SimulationGate gate = mock(SimulationGate.class);

    private TwitchRawQueries queries(ObjectProvider<SimulationGate> provider) {
        return new TwitchRawQueries(tokens, mock(OAuthTokenRepository.class), mock(UserAccountRepository.class),
                restClient, new RawProviderResponseSupport(new com.fasterxml.jackson.databind.ObjectMapper()),
                provider);
    }

    @Test
    void a_simulated_app_token_answers_no_game_without_a_call() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(false);

        var result = queries(SimulationGates.of(gate)).games("Fortnite");

        assertThat(result.hasError()).isFalse();
        assertThat(result.body()).contains("\"data\"");
        verifyNoInteractions(tokens, restClient);
    }

    @Test
    void a_real_app_token_probes_twitch() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(true);

        queries(SimulationGates.of(gate)).games("Fortnite");

        verify(tokens).getAppAccessToken();
    }

    @Test
    void without_the_simulation_the_probe_runs_as_before() {
        queries(SimulationGates.none()).games("Fortnite");

        verify(tokens).getAppAccessToken();
    }
}
