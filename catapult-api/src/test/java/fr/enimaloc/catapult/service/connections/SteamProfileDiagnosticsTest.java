package fr.enimaloc.catapult.service.connections;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SteamProfileDiagnosticsTest {

    private final SteamApiClient steam = mock(SteamApiClient.class);
    private final SteamApiKeyRotator rotator = mock(SteamApiKeyRotator.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private UserAccount channel;

    @BeforeEach
    void setUp() {
        channel = new UserAccount();
        channel.setSteamId("7656");
        when(steam.getProfileCacheTtl()).thenReturn(Duration.ofMinutes(30));
        when(steam.getProfileStatus(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(false, false)));
    }

    @Test
    void withoutSteamClient_nothingIsConnected() {
        SteamProfileDiagnostics diagnostics = new SteamProfileDiagnostics(Optional.empty(), Optional.empty(), encryption);

        assertThat(diagnostics.available()).isFalse();
        assertThat(diagnostics.diagnose(channel))
                .isEqualTo(new SteamProfileDto(false, false, false, false, 15L, false, false));
    }

    @Test
    void diagnose_probesTheProfile() {
        SteamProfileDiagnostics diagnostics = new SteamProfileDiagnostics(Optional.of(steam), Optional.of(rotator), encryption);

        assertThat(diagnostics.diagnose(channel).profilePrivate()).isTrue();
        verify(steam).getProfileStatus("7656", null);
    }

    @Test
    void snapshotWithoutProbe_doesntCallSteam() {
        SteamProfileDiagnostics diagnostics = new SteamProfileDiagnostics(Optional.of(steam), Optional.of(rotator), encryption);

        assertThat(diagnostics.snapshot(channel, false))
                .isEqualTo(new SteamProfileDto(true, false, false, false, 30L, false, false));
        verify(steam, never()).getProfileStatus(any(), any());
    }

    @Test
    void withoutKeyRotator_onlyTheClientsRateLimitCounts() {
        SteamProfileDiagnostics diagnostics = new SteamProfileDiagnostics(Optional.of(steam), Optional.empty(), encryption);

        assertThat(diagnostics.diagnose(channel).rateLimited()).isFalse();

        when(steam.isRateLimited()).thenReturn(true);
        assertThat(diagnostics.diagnose(channel).rateLimited()).isTrue();
        assertThat(diagnostics.diagnose(channel).profilePrivate()).isFalse();
    }

    @Test
    void blockedKeys_turnAPrivateProfileIntoRateLimiting() {
        when(rotator.isAllKeysBlocked()).thenReturn(true);
        SteamProfileDiagnostics diagnostics = new SteamProfileDiagnostics(Optional.of(steam), Optional.of(rotator), encryption);

        SteamProfileDto steamState = diagnostics.diagnose(channel);

        assertThat(steamState.rateLimited()).isTrue();
        assertThat(steamState.profilePrivate()).isFalse();
    }
}
