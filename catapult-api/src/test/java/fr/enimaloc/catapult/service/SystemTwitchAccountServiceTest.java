package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemTwitchAccountServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock OAuthTokenRepository tokenRepo;
    @Mock TokenEncryptionService encryption;
    @Mock RestClient restClient;

    SystemTwitchAccountService service;

    @BeforeEach
    void setup() {
        service = new SystemTwitchAccountService(userAccountRepository, tokenRepo, encryption, restClient);
        ReflectionTestUtils.setField(service, "twitchClientId", "client");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "secret");
        ReflectionTestUtils.setField(service, "modCacheTtlSeconds", 600L);
    }

    @Test
    void access_token_returns_null_when_no_system_account_exists() {
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.empty());
        assertThat(service.getAccessToken()).isNull();
        assertThat(service.getSystemTwitchId()).isNull();
    }

    @Test
    void access_token_returns_null_when_system_account_has_no_twitch_id() {
        UserAccount system = new UserAccount();
        system.setId(UUID.randomUUID());
        system.setSystemAccount(true);
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.of(system));

        assertThat(service.getAccessToken()).isNull();
    }

    @Test
    void access_token_returns_cached_value_when_fresh() {
        UserAccount system = systemWithTwitchId("bot123");
        OAuthToken token = new OAuthToken();
        token.setAccessToken("encrypted-access");
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.of(system));
        when(tokenRepo.findByUserAndProvider(system, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));
        when(encryption.decrypt("encrypted-access")).thenReturn("plain-access");

        assertThat(service.getAccessToken()).isEqualTo("plain-access");
        assertThat(service.getSystemTwitchId()).isEqualTo("bot123");
    }

    @Test
    void refresh_short_circuits_when_no_refresh_token_stored() {
        UserAccount system = systemWithTwitchId("bot123");
        OAuthToken token = new OAuthToken();
        token.setAccessToken("encrypted-access");
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.of(system));
        when(tokenRepo.findByUserAndProvider(system, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));

        Optional<String> result = service.refresh();
        assertThat(result).isEmpty();
    }

    private UserAccount systemWithTwitchId(String twitchId) {
        UserAccount system = new UserAccount();
        system.setId(UUID.randomUUID());
        system.setSystemAccount(true);
        system.setTwitchId(twitchId);
        return system;
    }
}
