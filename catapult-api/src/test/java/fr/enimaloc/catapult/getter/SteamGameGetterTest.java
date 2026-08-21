package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.SteamStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SteamGameGetterTest {

    @Mock SteamApiClient steamApiClient;
    @Mock TokenEncryptionService tokenEncryptionService;
    @Mock SteamStoreService steamStoreService;

    SteamGameGetter getter;

    @BeforeEach
    void setUp() {
        getter = new SteamGameGetter(steamApiClient, tokenEncryptionService, steamStoreService);
    }

    @Test
    void prefetchBatch_populatesCycleCacheFromBatchResult() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setSteamId("123");

        when(steamApiClient.getPlayerSummaries(List.of("123")))
            .thenReturn(CompletableFuture.completedFuture(
                Map.of("123", Optional.of(new SteamApiClient.PlayerSummary("456", "Game", "Streamer", "online")))
            ));

        getter.prefetchBatch(List.of(user)).get();

        Optional<DetectedGame> result = getter.getCurrentGame(user);
        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("Game");
    }

    @Test
    void prefetchBatch_returnsCompletedFuture_whenNoBatchIds() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setSteamId("123");
        user.setSteamPersonalToken("token"); // has personal token → excluded from batch

        CompletableFuture<Void> future = getter.prefetchBatch(List.of(user));

        assertThat(future).isCompleted();
    }

    @Test
    void getCurrentGame_usesPersonalToken_whenCacheMiss() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setSteamId("123");
        user.setSteamPersonalToken("encrypted");

        when(tokenEncryptionService.decrypt("encrypted")).thenReturn("raw-token");
        when(steamApiClient.getPlayerSummary("123", "raw-token"))
            .thenReturn(CompletableFuture.completedFuture(
                Optional.of(new SteamApiClient.PlayerSummary("789", "AnotherGame", "Streamer", "online"))
            ));

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("AnotherGame");
    }

    @Test
    void getCurrentGame_returnsEmpty_whenSteamIdNull() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_resolvesParentApp_whenPlaytestDetected() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setSteamId("123");

        when(steamApiClient.getPlayerSummaries(List.of("123")))
            .thenReturn(CompletableFuture.completedFuture(
                Map.of("123", Optional.of(new SteamApiClient.PlayerSummary("4519120", "Arctic Drive Playtest", "Streamer", "online")))
            ));
        when(steamStoreService.resolveEffectiveApp("4519120"))
            .thenReturn(Optional.of(new SteamStoreService.ResolvedParentApp("4009490", "Arctic Drive")));

        getter.prefetchBatch(List.of(user)).get();
        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("4009490");
        assertThat(result.get().getSourceName()).isEqualTo("Arctic Drive");
    }
}
