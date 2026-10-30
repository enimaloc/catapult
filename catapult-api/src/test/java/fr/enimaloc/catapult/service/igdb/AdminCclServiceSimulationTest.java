package fr.enimaloc.catapult.service.igdb;

import fr.enimaloc.catapult.repository.igdb.IgdbRatingDescriptorRepository;
import fr.enimaloc.catapult.repository.twitch.TwitchCclDefinitionRepository;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The CCL startup sync under the simulation: the Twitch part only runs while {@code twitch.app.app-token} is REAL,
 * the IGDB descriptors only while {@code igdb.game} is too.
 */
class AdminCclServiceSimulationTest {

    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token?client_id=twitch-id&client_secret=secret&grant_type=client_credentials";
    private static final String CCL_URL = "https://api.twitch.tv/helix/content_classification_labels";
    private static final String AGE_RATINGS_URL = "https://api.igdb.com/v4/age_ratings";

    SimulationGate gate = mock(SimulationGate.class);
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private AdminCclService service(ObjectProvider<SimulationGate> provider) {
        AdminCclService service = new AdminCclService(builder.build(), mock(TwitchCclDefinitionRepository.class),
                mock(IgdbRatingDescriptorRepository.class), TransactionOperations.withoutTransaction(), provider);
        ReflectionTestUtils.setField(service, "twitchClientId", "twitch-id");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "secret");
        ReflectionTestUtils.setField(service, "igdbClientId", "");
        return service;
    }

    private void json(String url, HttpMethod httpMethod, String body) {
        server.expect(requestTo(url)).andExpect(method(httpMethod))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @BeforeEach
    void igdbSimulated() {
        when(gate.real(null, SimulatedCalls.IGDB_GAME)).thenReturn(false);
    }

    @Test
    void a_simulated_app_token_sends_nothing() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(false);

        service(SimulationGates.of(gate)).init();

        server.verify();
    }

    @Test
    void a_real_app_token_syncs_twitch_but_not_the_simulated_igdb() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(true);
        json(TOKEN_URL, HttpMethod.POST, "{\"access_token\":\"app-token\"}");
        json(CCL_URL, HttpMethod.GET, "{\"data\":[]}");

        service(SimulationGates.of(gate)).init();

        server.verify();
    }

    @Test
    void without_the_simulation_the_sync_runs_as_before() {
        json(TOKEN_URL, HttpMethod.POST, "{\"access_token\":\"app-token\"}");
        json(CCL_URL, HttpMethod.GET, "{\"data\":[]}");
        json(AGE_RATINGS_URL, HttpMethod.POST, "[]");

        service(SimulationGates.none()).init();

        server.verify();
    }
}
