package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchTokenServiceTest {

    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private RestClient restClient;

    @Mock private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock private RestClient.RequestBodySpec postBodySpec;
    @Mock private RestClient.ResponseSpec postResponseSpec;

    @InjectMocks private TwitchTokenService service;

    private UserAccount user;
    private OAuthToken token;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        token = new OAuthToken();
        token.setAccessToken("encrypted-access");

        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString())).thenReturn(postBodySpec);
        doReturn(postBodySpec).when(postBodySpec).contentType(any());
        doReturn(postBodySpec).when(postBodySpec).body(any(Object.class));
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);

        ReflectionTestUtils.setField(service, "twitchClientId", "test-client-id");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "test-secret");
    }

    @Test
    void resolveAccessToken_notExpired_returnsDecryptedToken() {
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(tokenEncryptionService.decrypt("encrypted-access")).thenReturn("plain-access");

        String result = service.resolveAccessToken(token, user);

        assertThat(result).isEqualTo("plain-access");
        verify(restClient, never()).post();
    }

    @Test
    void resolveAccessToken_noExpiresAt_returnsDecryptedToken() {
        token.setExpiresAt(null);
        when(tokenEncryptionService.decrypt("encrypted-access")).thenReturn("plain-access");

        String result = service.resolveAccessToken(token, user);

        assertThat(result).isEqualTo("plain-access");
        verify(restClient, never()).post();
    }

    @Test
    void resolveAccessToken_expired_proactivelyRefreshesAndReturnsNewToken() {
        token.setExpiresAt(Instant.now().minusSeconds(1));
        token.setRefreshToken("encrypted-refresh");
        when(tokenEncryptionService.decrypt("encrypted-refresh")).thenReturn("plain-refresh");
        when(tokenEncryptionService.encrypt("new-access")).thenReturn("enc-new-access");
        when(tokenEncryptionService.encrypt("new-refresh")).thenReturn("enc-new-refresh");
        when(postResponseSpec.body(Map.class)).thenReturn(Map.of(
            "access_token", "new-access",
            "refresh_token", "new-refresh",
            "expires_in", 14400
        ));

        String result = service.resolveAccessToken(token, user);

        assertThat(result).isEqualTo("new-access");
        verify(oAuthTokenRepository).save(token);
    }

    @Test
    void resolveAccessToken_expiredAndRefreshFails_returnsOldDecryptedToken() {
        token.setExpiresAt(Instant.now().minusSeconds(1));
        token.setRefreshToken("encrypted-refresh");
        when(tokenEncryptionService.decrypt("encrypted-refresh")).thenReturn("plain-refresh");
        when(tokenEncryptionService.decrypt("encrypted-access")).thenReturn("plain-access");
        when(postResponseSpec.body(Map.class)).thenThrow(new RuntimeException("network error"));

        String result = service.resolveAccessToken(token, user);

        assertThat(result).isEqualTo("plain-access");
    }

    @Test
    void refreshAccessToken_noRefreshToken_returnsNull() {
        token.setRefreshToken(null);

        String result = service.refreshAccessToken(token, user);

        assertThat(result).isNull();
        verify(restClient, never()).post();
    }

    @Test
    void refreshAccessToken_success_savesUpdatedTokenAndReturnsNewAccess() {
        token.setRefreshToken("encrypted-refresh");
        when(tokenEncryptionService.decrypt("encrypted-refresh")).thenReturn("plain-refresh");
        when(tokenEncryptionService.encrypt("new-access")).thenReturn("enc-new-access");
        when(tokenEncryptionService.encrypt("new-refresh")).thenReturn("enc-new-refresh");
        when(postResponseSpec.body(Map.class)).thenReturn(Map.of(
            "access_token", "new-access",
            "refresh_token", "new-refresh",
            "expires_in", 14400
        ));

        String result = service.refreshAccessToken(token, user);

        assertThat(result).isEqualTo("new-access");
        assertThat(token.getAccessToken()).isEqualTo("enc-new-access");
        assertThat(token.getRefreshToken()).isEqualTo("enc-new-refresh");
        assertThat(token.getExpiresAt()).isAfter(Instant.now().plusSeconds(14000));
        verify(oAuthTokenRepository).save(token);
    }

    @Test
    void refreshAccessToken_endpointFails_returnsNull() {
        token.setRefreshToken("encrypted-refresh");
        when(tokenEncryptionService.decrypt("encrypted-refresh")).thenReturn("plain-refresh");
        when(postResponseSpec.body(Map.class)).thenThrow(new RuntimeException("network error"));

        String result = service.refreshAccessToken(token, user);

        assertThat(result).isNull();
        verify(oAuthTokenRepository, never()).save(any());
    }

    @Test
    void refreshAccessToken_responseNull_returnsNull() {
        token.setRefreshToken("encrypted-refresh");
        when(tokenEncryptionService.decrypt("encrypted-refresh")).thenReturn("plain-refresh");
        when(postResponseSpec.body(Map.class)).thenReturn(null);

        String result = service.refreshAccessToken(token, user);

        assertThat(result).isNull();
        verify(oAuthTokenRepository, never()).save(any());
    }
}
