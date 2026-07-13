package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class XboxUserTokenServiceTest {

    @Mock private ClientRegistrationRepository clientRegistrationRepository;
    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private TokenEncryptionService encryption;
    @Mock private XboxService xboxService;
    @Mock private OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient;

    private XboxUserTokenService service;
    private UserAccount user;
    private OAuthToken oAuthToken;

    @BeforeEach
    void setup() {
        service = new XboxUserTokenService(clientRegistrationRepository, oAuthTokenRepository,
                encryption, xboxService, refreshClient, new SimpleMeterRegistry());

        user = new UserAccount();
        user.setId(UUID.randomUUID());

        oAuthToken = new OAuthToken();
        oAuthToken.setUser(user);
        oAuthToken.setProvider(OAuthToken.Provider.XBOX);
        oAuthToken.setRefreshToken("enc-refresh");

        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX))
                .thenReturn(java.util.Optional.of(oAuthToken));

        ClientRegistration registration = ClientRegistration.withRegistrationId("xbox")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize")
                .tokenUri("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .build();
        when(clientRegistrationRepository.findByRegistrationId("xbox")).thenReturn(registration);

        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String arg = inv.getArgument(0);
            if ("enc-refresh".equals(arg)) return "refresh-clear";
            if (arg.startsWith("enc:")) return arg.substring(4);
            return arg;
        });
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        when(refreshClient.getTokenResponse(any(OAuth2RefreshTokenGrantRequest.class))).thenReturn(
                OAuth2AccessTokenResponse.withToken("msa-access")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(3600)
                        .refreshToken("refresh-new")
                        .build());

        var xboxToken = new XboxService.Token(Instant.now(), Instant.now().plusSeconds(86400), "xbl-token",
                new XboxService.DisplayClaims(new XboxService.DisplayClaims.Xui[]{
                        new XboxService.DisplayClaims.Xui("user-hash", "1234567890")
                }));
        when(xboxService.getXboxToken("d=msa-access")).thenReturn(xboxToken);
        when(xboxService.getXstsToken(eq(XboxService.XBOX_LIVE_RELYING_PARTY), any(XboxService.Token.class)))
                .thenReturn(xboxToken);
    }

    @Test
    void getToken_runsFullChainAndReturnsXstsSession() {
        var session = service.getToken(user);

        assertThat(session).isPresent();
        assertThat(session.get().token()).isEqualTo("xbl-token");
        assertThat(session.get().userHash()).isEqualTo("user-hash");
        assertThat(session.get().xuid()).isEqualTo("1234567890");
    }

    @Test
    void getToken_persistsRotatedRefreshTokenEncrypted() {
        service.getToken(user);

        assertThat(oAuthToken.getRefreshToken()).isEqualTo("enc:refresh-new");
        verify(oAuthTokenRepository).save(oAuthToken);
    }

    @Test
    void getToken_secondCallUsesCache() {
        service.getToken(user);
        service.getToken(user);

        verify(refreshClient, times(1)).getTokenResponse(any(OAuth2RefreshTokenGrantRequest.class));
    }

    @Test
    void getToken_noLinkedAccount_returnsEmpty() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX))
                .thenReturn(java.util.Optional.empty());

        assertThat(service.getToken(user)).isEmpty();
    }

    @Test
    void getToken_chainFailure_returnsEmptyWithoutThrowing() {
        when(refreshClient.getTokenResponse(any(OAuth2RefreshTokenGrantRequest.class)))
                .thenThrow(new RuntimeException("invalid_grant"));

        assertThat(service.getToken(user)).isEmpty();
    }

    @Test
    void evict_forcesRefreshOnNextCall() {
        service.getToken(user);

        service.evict(user.getId());
        service.getToken(user);

        verify(refreshClient, times(2)).getTokenResponse(any(OAuth2RefreshTokenGrantRequest.class));
    }
}
