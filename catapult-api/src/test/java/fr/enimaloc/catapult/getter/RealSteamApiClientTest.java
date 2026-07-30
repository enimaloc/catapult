package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealSteamApiClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private SteamRateLimiter rateLimiter;
    @Mock private SteamApiKeyRotator rotator;

    private RealSteamApiClient client;

    @BeforeEach
    void setUp() {
        client = new RealSteamApiClient(restClient, rateLimiter, Runnable::run, rotator,
            new ExternalApiObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry()));
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString(), any(Object[].class));
        doReturn(responseSpec).when(headersSpec).retrieve();
        when(rotator.nextKey()).thenReturn(Optional.of("api-key"));
        when(rateLimiter.acquire(any())).thenReturn(true);
        when(rateLimiter.acquireBlocking(any())).thenReturn(true);
    }

    @Test
    void getPlayerProfileParsesDisplayNameAndOnlineStatus() {
        Map<String, Object> body = Map.of("response", Map.of("players", List.of(
            Map.of("personaname", "MyStreamer", "personastate", 1,
                "gameid", "1091500", "gameextrainfo", "Valorant"))));
        doReturn(body).when(responseSpec).body(Map.class);

        Optional<SteamApiClient.PlayerSummary> result =
            client.getPlayerProfile("123", null).join();

        assertThat(result).isPresent();
        assertThat(result.get().displayName()).isEqualTo("MyStreamer");
        assertThat(result.get().onlineStatus()).isEqualTo("online");
        assertThat(result.get().gameName()).isEqualTo("Valorant");
    }

    @Test
    void getPlayerProfileMapsEachPersonaStateToItsVocabularyWord() {
        int[] states = {0, 1, 2, 3, 4, 5, 6};
        String[] expected = {"offline", "online", "busy", "away", "snooze", "looking to trade", "looking to play"};

        for (int i = 0; i < states.length; i++) {
            Map<String, Object> body = Map.of("response", Map.of("players", List.of(
                Map.of("personaname", "P", "personastate", states[i]))));
            doReturn(body).when(responseSpec).body(Map.class);

            Optional<SteamApiClient.PlayerSummary> result = client.getPlayerProfile("123", null).join();
            assertThat(result).isPresent();
            assertThat(result.get().onlineStatus()).as("personastate=" + states[i]).isEqualTo(expected[i]);
        }
    }

    @Test
    void getPlayerProfileReturnsEmptyGameFieldsWhenNotPlayingAnything() {
        Map<String, Object> body = Map.of("response", Map.of("players", List.of(
            Map.of("personaname", "MyStreamer", "personastate", 1))));
        doReturn(body).when(responseSpec).body(Map.class);

        Optional<SteamApiClient.PlayerSummary> result = client.getPlayerProfile("123", null).join();

        assertThat(result).isPresent();
        assertThat(result.get().gameId()).isNull();
        assertThat(result.get().gameName()).isNull();
    }

    @Test
    void getPlayerProfileReturnsEmptyWhenNoPlayerFound() {
        Map<String, Object> body = Map.of("response", Map.of("players", List.of()));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(client.getPlayerProfile("123", null).join()).isEmpty();
    }

    @Test
    void getPlaytimeReturnsMatchingGamesPlaytimeAsADuration() {
        Map<String, Object> body = Map.of("response", Map.of("games", List.of(
            Map.of("appid", 1091500, "playtime_forever", 732),
            Map.of("appid", 730, "playtime_forever", 60))));
        doReturn(body).when(responseSpec).body(Map.class);

        Optional<Duration> result = client.getPlaytime("123", "1091500", null).join();

        assertThat(result).contains(Duration.ofMinutes(732));
    }

    @Test
    void getPlaytimeReturnsEmptyWhenGameNotInOwnedList() {
        Map<String, Object> body = Map.of("response", Map.of("games", List.of(
            Map.of("appid", 730, "playtime_forever", 60))));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(client.getPlaytime("123", "1091500", null).join()).isEmpty();
    }
}
