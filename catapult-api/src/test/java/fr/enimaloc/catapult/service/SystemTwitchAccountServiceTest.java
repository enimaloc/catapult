package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemTwitchAccountServiceTest {

    @Mock OAuthTokenRepository tokenRepo;
    @Mock TokenEncryptionService encryption;
    @Mock RestClient restClient;

    SystemTwitchAccountService service;

    @BeforeEach
    void setup() {
        service = new SystemTwitchAccountService(tokenRepo, encryption, restClient);
        ReflectionTestUtils.setField(service, "systemUserId", "bot123");
        ReflectionTestUtils.setField(service, "modCacheTtlSeconds", 600L);
        ReflectionTestUtils.setField(service, "twitchClientId", "client");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "secret");
        ReflectionTestUtils.setField(service, "seedRefreshToken", "");
    }

    @Test
    void init_loads_existing_token_from_repo() {
        OAuthToken token = new OAuthToken();
        token.setProvider(OAuthToken.Provider.SYSTEM);
        token.setAccessToken("encrypted-access");
        token.setRefreshToken("encrypted-refresh");
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(tokenRepo.findByProviderAndUserIsNull(OAuthToken.Provider.SYSTEM))
            .thenReturn(Optional.of(token));
        when(encryption.decrypt("encrypted-access")).thenReturn("access-token");

        service.init();

        assertThat(service.getAccessToken()).isEqualTo("access-token");
        assertThat(service.getSystemTwitchId()).isEqualTo("bot123");
    }

    @Test
    void init_with_no_seed_and_no_token_does_nothing_silently() {
        when(tokenRepo.findByProviderAndUserIsNull(OAuthToken.Provider.SYSTEM))
            .thenReturn(Optional.empty());

        // should not throw, just log a warn
        service.init();
        assertThat(service.getAccessToken()).isNull();
    }
}
