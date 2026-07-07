package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MinecraftTokenServiceTest {

    @Mock private MsaAuthClient msaAuthClient;
    @Mock private XboxService xboxService;
    @Mock private MinecraftService minecraftService;
    @Mock private TokenEncryptionService encryption;
    @Mock private MinecraftServiceAccountRepository accountRepository;

    private MinecraftTokenService service;
    private MinecraftServiceAccount account;

    @BeforeEach
    void setup() {
        service = new MinecraftTokenService(msaAuthClient, xboxService, minecraftService,
                encryption, accountRepository, new SimpleMeterRegistry());

        account = new MinecraftServiceAccount();
        account.setId(UUID.randomUUID());
        account.setLabel("CatapultBot1");
        account.setMsaRefreshToken("enc-refresh");

        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String arg = inv.getArgument(0);
            if ("enc-refresh".equals(arg)) return "refresh-clear";
            if (arg.startsWith("enc:")) return arg.substring(4);
            return arg;
        });
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(msaAuthClient.refresh("refresh-clear"))
                .thenReturn(new MsaAuthClient.MsaTokens("msa-access", "refresh-new", 3600));

        var xboxToken = new XboxService.Token(Instant.now(), Instant.now().plusSeconds(86400), "xbl", null);
        when(xboxService.getXboxToken("d=msa-access")).thenReturn(xboxToken);
        when(xboxService.getXstsToken(any(XboxService.Token.class))).thenReturn(xboxToken);
        when(minecraftService.getMinecraftToken(any(XboxService.Token.class)))
                .thenReturn(new MinecraftService.Token("user", "mc-token", 86400, null, "Bearer", null));
    }

    @Test
    void getToken_runsFullChainAndReturnsMinecraftToken() {
        assertThat(service.getToken(account)).contains("mc-token");
    }

    @Test
    void getToken_persistsRotatedRefreshTokenEncrypted() {
        service.getToken(account);

        assertThat(account.getMsaRefreshToken()).isEqualTo("enc:refresh-new");
        verify(accountRepository).save(account);
    }

    @Test
    void getToken_secondCallUsesCache() {
        service.getToken(account);
        service.getToken(account);

        verify(msaAuthClient, times(1)).refresh(anyString());
    }

    @Test
    void getToken_chainFailure_returnsEmptyWithoutThrowing() {
        when(msaAuthClient.refresh(anyString())).thenThrow(new RuntimeException("revoked"));

        assertThat(service.getToken(account)).isEmpty();
    }

    @Test
    void evict_forcesRefreshOnNextCall() {
        service.getToken(account);

        var xboxToken = new XboxService.Token(Instant.now(), Instant.now().plusSeconds(86400), "xbl", null);
        when(msaAuthClient.refresh("refresh-new"))
                .thenReturn(new MsaAuthClient.MsaTokens("msa-access-2", "refresh-newer", 3600));
        when(xboxService.getXboxToken(anyString())).thenReturn(xboxToken);

        service.evict(account.getId());
        assertThat(service.getToken(account)).contains("mc-token");

        verify(msaAuthClient, times(2)).refresh(anyString());
    }
}
