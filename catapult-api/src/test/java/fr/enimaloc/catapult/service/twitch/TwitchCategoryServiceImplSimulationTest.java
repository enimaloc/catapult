package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.repository.igdb.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.twitch.TwitchCategoryCacheRepository;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The category prewarm under the simulation: only run while the global category search is REAL. */
class TwitchCategoryServiceImplSimulationTest {

    RestClient restClient = mock(RestClient.class);
    TwitchCategoryCacheRepository cacheRepo = mock(TwitchCategoryCacheRepository.class);
    SimulationGate gate = mock(SimulationGate.class);

    private TwitchCategoryServiceImpl service(ObjectProvider<SimulationGate> provider) {
        TwitchCategoryServiceImpl service = new TwitchCategoryServiceImpl(cacheRepo,
                mock(IgdbGameExternalIdRepository.class), mock(IgdbService.class), restClient,
                new ExternalApiObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry()), provider);
        ReflectionTestUtils.setField(service, "twitchClientId", "id");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "secret");
        ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryServiceImpl.PrewarmMode.TOP);
        return service;
    }

    @Test
    void a_simulated_category_search_skips_the_prewarm() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_CATEGORY_SEARCH)).thenReturn(false);

        service(SimulationGates.of(gate)).prewarmCategoryCache();

        verifyNoInteractions(restClient, cacheRepo);
    }

    @Test
    void a_real_category_search_prewarms() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_CATEGORY_SEARCH)).thenReturn(true);

        service(SimulationGates.of(gate)).prewarmCategoryCache();

        // the app token request goes out (the mock answers nothing, so the prewarm stops there)
        verify(restClient, atLeastOnce()).post();
    }

    @Test
    void without_the_simulation_the_prewarm_runs_as_before() {
        service(SimulationGates.none()).prewarmCategoryCache();

        verify(restClient, atLeastOnce()).post();
    }
}
