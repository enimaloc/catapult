package fr.enimaloc.catapult.getter;

import tools.jackson.databind.JsonNode;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"rawtypes", "unchecked"})
class XboxGameGetterTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private XboxUserTokenService tokenService;

    private final ExternalApiObservations apiObservations =
        new ExternalApiObservations(ObservationRegistry.create(), new SimpleMeterRegistry());

    private XboxGameGetter getter;
    private UserAccount user;

    @BeforeEach
    void setup() {
        getter = new XboxGameGetter(restClient, oAuthTokenRepository, tokenService, apiObservations, Optional.empty());
        ReflectionTestUtils.setField(getter, "titleIdBlacklistRaw", "1626579248");
        ReflectionTestUtils.invokeMethod(getter, "init");

        user = new UserAccount();
        user.setId(UUID.randomUUID());

        OAuthToken linked = new OAuthToken();
        linked.setUser(user);
        linked.setProvider(OAuthToken.Provider.XBOX);
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX))
            .thenReturn(Optional.of(linked));

        when(tokenService.getToken(user)).thenReturn(
            Optional.of(new XboxUserTokenService.XstsSession("xsts-token", "user-hash", "1234567890")));

        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString(), any(Object[].class));
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void getCurrentGame_activeNonHomeTitle_returnsDetectedGame() {
        Map<String, Object> response = Map.of("devices", List.of(
            Map.of("titles", List.of(
                Map.of("id", "home-id", "name", "Home", "state", "Active"),
                Map.of("id", "1234", "name", "Halo Infinite", "state", "Active")
            ))
        ));
        doReturn(response).when(responseSpec).body(Map.class);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("1234");
        assertThat(result.get().getSourceName()).isEqualTo("Halo Infinite");
        assertThat(result.get().getSourceType()).isEqualTo(GameBinding.SourceType.XBOX);
    }

    @Test
    void getCurrentGame_blacklistedTitle_skipsAndReturnsNextTitle() {
        Map<String, Object> response = Map.of("devices", List.of(
            Map.of("titles", List.of(
                Map.of("id", "1626579248", "name", "Xbox", "state", "Active"),
                Map.of("id", "1234", "name", "Halo Infinite", "state", "Active")
            ))
        ));
        doReturn(response).when(responseSpec).body(Map.class);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("1234");
        assertThat(result.get().getSourceName()).isEqualTo("Halo Infinite");
    }

    @Test
    void getCurrentGame_onlyBlacklistedTitle_returnsEmpty() {
        Map<String, Object> response = Map.of("devices", List.of(
            Map.of("titles", List.of(
                Map.of("id", "1626579248", "name", "Xbox", "state", "Active")
            ))
        ));
        doReturn(response).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_onlyHomeTitle_returnsEmpty() {
        Map<String, Object> response = Map.of("devices", List.of(
            Map.of("titles", List.of(
                Map.of("id", "home-id", "name", "Home", "state", "Active")
            ))
        ));
        doReturn(response).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_onlyOnlineTitle_returnsEmpty() {
        Map<String, Object> response = Map.of("devices", List.of(
            Map.of("titles", List.of(
                Map.of("id", "9NJBMZPGPVSS", "name", "Online", "state", "Active")
            ))
        ));
        doReturn(response).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_onlineTitle_skipsAndReturnsNextTitle() {
        Map<String, Object> response = Map.of("devices", List.of(
            Map.of("titles", List.of(
                Map.of("id", "9NJBMZPGPVSS", "name", "Online", "state", "Active"),
                Map.of("id", "1234", "name", "Halo Infinite", "state", "Active")
            ))
        ));
        doReturn(response).when(responseSpec).body(Map.class);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("1234");
        assertThat(result.get().getSourceName()).isEqualTo("Halo Infinite");
    }

    private static Map<String, Object> titlehub(String type, String productId) {
        return Map.of("titles", List.of(Map.of(
            "type", type,
            "detail", Map.of("availabilities", List.of(Map.of("AvailabilityId", productId))))));
    }

    private static Map<String, Object> presence(Map<String, Object>... titles) {
        return Map.of("devices", List.of(Map.of("titles", List.of(titles))));
    }

    @Test
    void getCurrentGame_titlehubTypeApp_isNotAGame() {
        doReturn(presence(Map.of("id", "5678", "name", "Microsoft Store", "state", "Active")),
            titlehub("App", "9QPQ53BM3J3T")).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_appThenGame_returnsTheGame() {
        doReturn(presence(
                Map.of("id", "5678", "name", "Twitch", "state", "Active"),
                Map.of("id", "1234", "name", "Halo Infinite", "state", "Active")),
            titlehub("App", "9Q5CCV8BCC32"), titlehub("game", "9NP1P1WFS0LB")).when(responseSpec).body(Map.class);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("9NP1P1WFS0LB");
        assertThat(result.get().getSourceName()).isEqualTo("Halo Infinite");
    }

    @Test
    void getCurrentGame_titlehubTypeGame_returnsProductId() {
        doReturn(presence(Map.of("id", "1234", "name", "Halo Infinite", "state", "Active")),
            titlehub("Game", "9NP1P1WFS0LB")).when(responseSpec).body(Map.class);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("9NP1P1WFS0LB");
    }

    @Test
    void getCurrentGame_titlehubTypeDGame_isAGame() {
        doReturn(presence(Map.of("id", "1234", "name", "Halo Infinite", "state", "Active")),
            titlehub("DGame", "9NP1P1WFS0LB")).when(responseSpec).body(Map.class);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("9NP1P1WFS0LB");
    }

    @Test
    void getCurrentGame_titlehubFailure_fallsBackToTitleId() {
        Map<String, Object> presence = presence(Map.of("id", "1234", "name", "Halo Infinite", "state", "Active"));
        when(responseSpec.body(Map.class)).thenReturn(presence).thenThrow(new RuntimeException("400"));

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("1234");
    }

    @Test
    void getCurrentGame_unknownTitleWithoutName_skipsTheDetection() {
        // Seen in prod: a new title the presence lists without a name, which Titlehub answers 404 with no titles
        Map<String, Object> presence = presence(Map.of("id", "1800597931", "name", "", "state", "Active"));
        when(responseSpec.body(Map.class)).thenReturn(presence).thenThrow(new RuntimeException("404 Not Found"));

        assertThatThrownBy(() -> getter.getCurrentGame(user)).isInstanceOf(DetectionTimeoutException.class);
    }

    @Test
    void getCurrentGame_titlehubWithoutTitlesAndNoName_skipsTheDetection() {
        Map<String, Object> presence = presence(Map.of("id", "1800597931", "name", " ", "state", "Active"));
        doReturn(presence, Map.of("xuid", "1", "titles", List.of())).when(responseSpec).body(Map.class);

        assertThatThrownBy(() -> getter.getCurrentGame(user)).isInstanceOf(DetectionTimeoutException.class);
    }

    @Test
    void getCurrentGame_inactiveTitle_returnsEmpty() {
        Map<String, Object> response = Map.of("devices", List.of(
            Map.of("titles", List.of(
                Map.of("id", "1234", "name", "Halo Infinite", "state", "NotActive")
            ))
        ));
        doReturn(response).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_noDevices_returnsEmpty() {
        doReturn(Map.of()).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_notLinked_returnsEmptyWithoutCallingTokenService() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX))
            .thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_noXstsToken_returnsEmpty() {
        when(tokenService.getToken(user)).thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_apiFailure_returnsEmptyWithoutThrowing() {
        doThrow(new RuntimeException("500")).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    private static HttpServerErrorException serviceUnavailable() {
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY, null, null);
    }

    @Test
    void getCurrentGame_presenceUnavailable_skipsTheDetection() {
        doThrow(serviceUnavailable()).when(responseSpec).body(Map.class);

        assertThatThrownBy(() -> getter.getCurrentGame(user)).isInstanceOf(DetectionTimeoutException.class);
    }

    @Test
    void getCurrentGame_titlehubUnavailable_skipsTheDetection() {
        Map<String, Object> presence = presence(Map.of("id", "1234", "name", "Halo Infinite", "state", "Active"));
        when(responseSpec.body(Map.class)).thenReturn(presence).thenThrow(serviceUnavailable());

        assertThatThrownBy(() -> getter.getCurrentGame(user)).isInstanceOf(DetectionTimeoutException.class);
    }

    // --- Detection trace ---

    @Test
    void traceReason_noLinkedToken() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX))
            .thenReturn(Optional.empty());

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("no linked Xbox token");
    }

    @Test
    void traceReason_tokenRefreshFailed() {
        when(tokenService.getToken(user)).thenReturn(Optional.empty());

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("token refresh failed");
    }

    @Test
    void traceReason_noActiveTitle() {
        doReturn(Map.of("devices", List.of())).when(responseSpec).body(Map.class);

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("no active title");
    }

    @Test
    void traceReason_onlyAppsAreActive() {
        doReturn(presence(Map.of("id", "5678", "name", "Microsoft Store", "state", "Active")),
            titlehub("App", "9QPQ53BM3J3T")).when(responseSpec).body(Map.class);

        JsonNode entry = TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user));

        assertThat(entry.path("reason").asString()).isEqualTo("only apps are active");
        assertThat(entry.at("/details/skipped/0").asString()).isEqualTo("5678 (App)");
    }
}
