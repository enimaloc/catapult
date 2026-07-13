package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.service.XboxUserTokenService;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
        getter = new XboxGameGetter(restClient, oAuthTokenRepository, tokenService, apiObservations);

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
        doThrow(new RuntimeException("503")).when(responseSpec).body(Map.class);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }
}
