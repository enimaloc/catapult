package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The chat commands' raw Steam store calls under the simulation: only sent while {@code steam.store.app-details} is REAL. */
@SuppressWarnings("rawtypes")
class DefaultChatCommandServiceGatewaySimulationTest {

    RestClient restClient = mock(RestClient.class);
    RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
    SteamStoreService steamStore = mock(SteamStoreService.class);
    SimulationGate gate = mock(SimulationGate.class);

    @BeforeEach
    void setUp() {
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString(), any(Object[].class));
        doReturn(responseSpec).when(headersSpec).retrieve();
        when(responseSpec.body(Map.class)).thenReturn(Map.of());
        when(steamStore.resolveEffectiveApp(anyString())).thenReturn(Optional.empty());
    }

    private DefaultChatCommandServiceGateway gateway(ObjectProvider<SimulationGate> provider) {
        return new DefaultChatCommandServiceGateway(mock(IgdbClient.class), mock(IgdbService.class), restClient,
                new ExternalApiObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry()), steamStore, provider);
    }

    @Test
    void a_simulated_store_answers_nothing_without_a_call() {
        when(gate.real(null, SimulatedCalls.STEAM_STORE_APP_DETAILS)).thenReturn(false);
        DefaultChatCommandServiceGateway gateway = gateway(SimulationGates.of(gate));

        assertThat(gateway.steamPrice("1091500")).isEmpty();
        assertThat(gateway.steamGame("1091500", null)).isEmpty();

        verifyNoInteractions(restClient);
    }

    @Test
    void a_real_store_is_called() {
        when(gate.real(null, SimulatedCalls.STEAM_STORE_APP_DETAILS)).thenReturn(true);
        DefaultChatCommandServiceGateway gateway = gateway(SimulationGates.of(gate));

        gateway.steamPrice("1091500");
        gateway.steamGame("1091500", null);

        verify(restClient, times(2)).get();
    }

    @Test
    void without_the_simulation_the_store_is_called_as_before() {
        DefaultChatCommandServiceGateway gateway = gateway(SimulationGates.none());

        gateway.steamPrice("1091500");
        gateway.steamGame("1091500", null);

        verify(restClient, times(2)).get();
    }
}
