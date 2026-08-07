package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.TwitchTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAdminProviderTwitchControllerTest {

    @Mock private TwitchTokenService twitchTokenService;
    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private ApiAdminProviderTwitchController controller;
    private final RawProviderResponseSupport rawSupport =
            new RawProviderResponseSupport(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "rawSupport", rawSupport);
        ReflectionTestUtils.setField(controller, "twitchClientId", "test-client-id");
        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(any(String.class));
        doReturn(headersSpec).when(headersSpec).header(any(), any());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void games_success_usesAppTokenAndReturnsRawJson() {
        when(twitchTokenService.getAppAccessToken()).thenReturn("app-token");
        doReturn("{\"data\":[{\"id\":\"33214\",\"name\":\"Fortnite\"}]}").when(responseSpec).body(String.class);

        var result = controller.games("Fortnite");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"name\" : \"Fortnite\"");
    }

    @Test
    void games_tokenFetchFails_returnsRawErrorInsteadOfThrowing() {
        when(twitchTokenService.getAppAccessToken()).thenThrow(new IllegalStateException("token endpoint down"));

        var result = controller.games("Fortnite");

        assertThat(result.hasError()).isTrue();
        assertThat(result.error()).contains("token endpoint down");
    }

    @Test
    void users_unknownUserId_throws404() {
        UUID userId = UUID.randomUUID();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.users(userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void users_noTwitchTokenLinked_throws404() {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.users(userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void users_linkedUser_returnsRawJson() {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        OAuthToken token = new OAuthToken();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
        when(twitchTokenService.resolveAccessToken(token, user)).thenReturn("user-token");
        doReturn("{\"data\":[{\"id\":\"123\",\"login\":\"someuser\"}]}").when(responseSpec).body(String.class);

        var result = controller.users(userId);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"login\" : \"someuser\"");
    }
}
