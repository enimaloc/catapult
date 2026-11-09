package fr.enimaloc.catapult.service.igdb;

import fr.enimaloc.catapult.repository.igdb.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.twitch.TwitchCclDefinitionRepository;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The IGDB app token under the simulation: only fetched from Twitch while {@code twitch.app.app-token} is REAL. */
class IgdbServiceSimulationTest {

    RestClient restClient = mock(RestClient.class);
    SimulationGate gate = mock(SimulationGate.class);

    private IgdbService service(ObjectProvider<SimulationGate> provider) {
        IgdbService service = new IgdbService(mock(IgdbClient.class), mock(IgdbGameCacheRepository.class),
                mock(IgdbGameExternalIdRepository.class), mock(IgdbGameCclRepository.class),
                mock(TwitchCclDefinitionRepository.class), mock(SteamStoreService.class), restClient,
                new SimpleMeterRegistry(), provider);
        ReflectionTestUtils.setField(service, "clientId", "client");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        return service;
    }

    @Test
    void a_simulated_app_token_is_never_fetched() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(false);

        assertThat(service(SimulationGates.of(gate)).getAppToken()).isEmpty();

        verifyNoInteractions(restClient);
    }

    @Test
    void a_real_app_token_is_fetched() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(true);

        service(SimulationGates.of(gate)).getAppToken();

        verify(restClient).post();
    }

    @Test
    void without_the_simulation_the_token_is_fetched_as_before() {
        service(SimulationGates.none()).getAppToken();

        verify(restClient).post();
    }
}
