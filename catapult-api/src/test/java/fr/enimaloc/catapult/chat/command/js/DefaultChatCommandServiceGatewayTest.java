package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.IgdbClient;
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
import proto.Game;

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
class DefaultChatCommandServiceGatewayTest {

    @Mock private IgdbClient igdbClient;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private DefaultChatCommandServiceGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new DefaultChatCommandServiceGateway(igdbClient, restClient,
            new ExternalApiObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry()));
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString(), any(Object[].class));
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void igdbGameNameReturnsFirstResultName() {
        Game game = mock(Game.class);
        when(game.getName()).thenReturn("VALORANT");
        when(igdbClient.searchByName("Valorant", "")).thenReturn(List.of(game));

        assertThat(gateway.igdbGameName("Valorant")).contains("VALORANT");
    }

    @Test
    void igdbGameNameReturnsEmptyWhenNoResults() {
        when(igdbClient.searchByName("Unknown", "")).thenReturn(List.of());

        assertThat(gateway.igdbGameName("Unknown")).isEmpty();
    }

    @Test
    void igdbGameNameReturnsEmptyWhenClientThrows() {
        when(igdbClient.searchByName("Boom", "")).thenThrow(new RuntimeException("igdb down"));

        assertThat(gateway.igdbGameName("Boom")).isEmpty();
    }

    @Test
    void twitchOwnDisplayNameReturnsTwitchUsername() {
        UserAccount user = new UserAccount();
        user.setTwitchUsername("SomeStreamer");

        assertThat(gateway.twitchOwnDisplayName(user)).contains("SomeStreamer");
    }

    @Test
    void twitchOwnDisplayNameReturnsEmptyForNullUser() {
        assertThat(gateway.twitchOwnDisplayName(null)).isEmpty();
    }

    @Test
    void steamPriceReturnsFormattedPrice() {
        Map<String, Object> body = Map.of("1091500", Map.of(
            "success", true,
            "data", Map.of("price_overview", Map.of("final_formatted", "59,99€"))
        ));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(gateway.steamPrice("1091500")).contains("59,99€");
    }

    @Test
    void steamPriceReturnsEmptyWhenSuccessFalse() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", false));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(gateway.steamPrice("1091500")).isEmpty();
    }

    @Test
    void steamPriceReturnsEmptyWhenRestClientThrows() {
        doReturn(getSpec).when(restClient).get();
        when(getSpec.uri(anyString(), any(Object[].class))).thenThrow(new RuntimeException("network down"));

        assertThat(gateway.steamPrice("1091500")).isEmpty();
    }

    @Test
    void steamGameReturnsTheDataObjectWhenFound() {
        Map<String, Object> body = Map.of("1091500", Map.of(
            "success", true,
            "data", Map.of("name", "Cyberpunk 2077", "type", "game")
        ));
        doReturn(body).when(responseSpec).body(Map.class);

        Optional<Object> result = gateway.steamGame("1091500", null);

        assertThat(result).isPresent();
        assertThat(((Map<?, ?>) result.get()).get("name")).isEqualTo("Cyberpunk 2077");
    }

    @Test
    void steamGameDefaultsToEnglishWhenNoLocaleGiven() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", true, "data", Map.of("name", "Game")));
        doReturn(body).when(responseSpec).body(Map.class);

        gateway.steamGame("1091500", null);

        org.mockito.Mockito.verify(getSpec).uri(anyString(), org.mockito.ArgumentMatchers.eq("1091500"), org.mockito.ArgumentMatchers.eq("english"));
    }

    @Test
    void steamGamePassesThroughAnExplicitLocale() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", true, "data", Map.of("name", "Jeu")));
        doReturn(body).when(responseSpec).body(Map.class);

        gateway.steamGame("1091500", "french");

        org.mockito.Mockito.verify(getSpec).uri(anyString(), org.mockito.ArgumentMatchers.eq("1091500"), org.mockito.ArgumentMatchers.eq("french"));
    }

    @Test
    void steamGameReturnsEmptyWhenSuccessFalse() {
        Map<String, Object> body = Map.of("1091500", Map.of("success", false));
        doReturn(body).when(responseSpec).body(Map.class);

        assertThat(gateway.steamGame("1091500", null)).isEmpty();
    }

    @Test
    void steamGameReturnsEmptyWhenRestClientThrows() {
        doReturn(getSpec).when(restClient).get();
        when(getSpec.uri(anyString(), any(Object[].class))).thenThrow(new RuntimeException("network down"));

        assertThat(gateway.steamGame("1091500", null)).isEmpty();
    }
}
