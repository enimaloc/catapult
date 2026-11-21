package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.speedrun.SpeedrunAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpeedrunPersonalServiceTest {

    private static final SpeedrunRun PB = new SpeedrunRun("o1y9j9v6", "Celeste", "c1", "Any%", 42,
            new BigDecimal("1612.5"), LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T20:00:00Z"),
            "https://www.speedrun.com/celeste/run/pb");
    private static final SpeedrunRun NEW = new SpeedrunRun("o1y9j9v6", "Celeste", "c1", "Any%", null,
            new BigDecimal("1600.0"), null, Instant.parse("2026-11-20T10:00:00Z"),
            "https://www.speedrun.com/celeste/run/new");
    private static final SpeedrunIdentity BY_KEY = new SpeedrunIdentity("k1", "KeyOwner",
            "https://www.speedrun.com/users/KeyOwner", SpeedrunIdentity.Source.API_KEY);
    private static final SpeedrunIdentity BY_TWITCH = new SpeedrunIdentity("t1", "Streamer",
            "https://www.speedrun.com/users/Streamer", SpeedrunIdentity.Source.TWITCH);

    private final GameEnrichmentCacheRepository cacheRepository = mock(GameEnrichmentCacheRepository.class);
    private final SpeedrunAccountRepository accounts = mock(SpeedrunAccountRepository.class);
    private final SpeedrunClient client = mock(SpeedrunClient.class);
    private final SpeedrunRateLimiter limiter = mock(SpeedrunRateLimiter.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final Map<String, GameEnrichmentCacheEntry> store = new HashMap<>();
    private final UserAccount user = new UserAccount();
    private SpeedrunPersonalService service;

    @BeforeEach
    void setUp() {
        when(cacheRepository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(cacheRepository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        when(limiter.member()).thenReturn(FetchBudget.UNLIMITED);
        user.setId(UUID.randomUUID());
        service = new SpeedrunPersonalService(
                new EnrichmentCache(cacheRepository, properties, JsonMapper.builder().build()), client, limiter,
                accounts, encryption, properties);
    }

    private SpeedrunAccount keyRow(String keyUserId, boolean invalid) {
        SpeedrunAccount row = new SpeedrunAccount(user.getId());
        row.setApiKeyEnc("enc");
        row.setKeyUserId(keyUserId);
        row.setKeyInvalid(invalid);
        return row;
    }

    @Test
    void personalBestsCachedPerAccountAndGame() {
        when(client.personalBests("k1", "o1y9j9v6")).thenReturn(List.of(PB));
        when(client.personalBests("k1", null)).thenReturn(List.of(PB, PB));

        assertThat(service.personalBests("k1", "o1y9j9v6", FetchBudget.UNLIMITED)).contains(List.of(PB));
        assertThat(service.personalBests("k1", "o1y9j9v6", FetchBudget.UNLIMITED)).contains(List.of(PB));
        assertThat(service.personalBests("k1", null, FetchBudget.UNLIMITED)).contains(List.of(PB, PB));

        verify(client, times(1)).personalBests("k1", "o1y9j9v6");
        assertThat(store).containsKeys("speedrun-pbs|pb:k1:o1y9j9v6", "speedrun-pbs|pb:k1:all");
    }

    @Test
    void noPersonalBestIsAnEmptyListNotUnknown() {
        when(client.personalBests("k1", "o1y9j9v6")).thenReturn(List.of());

        assertThat(service.personalBests("k1", "o1y9j9v6", FetchBudget.UNLIMITED)).contains(List.of());
        assertThat(service.personalBests("k1", "o1y9j9v6", FetchBudget.UNLIMITED)).contains(List.of());
        verify(client, times(1)).personalBests("k1", "o1y9j9v6");
    }

    @Test
    void pendingRunsSendTheKeyAndAreCachedPerAccount() {
        when(client.pendingRuns("k1", null, "secret")).thenReturn(List.of(NEW));

        assertThat(service.pendingRuns(BY_KEY, null, Optional.of("secret"), FetchBudget.UNLIMITED))
                .contains(List.of(NEW));
        assertThat(service.pendingRuns(BY_KEY, null, Optional.of("secret"), FetchBudget.UNLIMITED))
                .contains(List.of(NEW));

        verify(client, times(1)).pendingRuns("k1", null, "secret");
        assertThat(store).containsKey("speedrun-pending|pending:k1:all");
        assertThat(store.get("speedrun-pending|pending:k1:all").getPayload()).doesNotContain("secret");
    }

    @Test
    void pendingRunsWithoutKeyAreStillRead() {
        when(client.pendingRuns("t1", "o1y9j9v6", null)).thenReturn(List.of(NEW));

        assertThat(service.pendingRuns(BY_TWITCH, "o1y9j9v6", Optional.empty(), FetchBudget.UNLIMITED))
                .contains(List.of(NEW));
        assertThat(store).containsKey("speedrun-pending|pending:t1:o1y9j9v6");
    }

    @Test
    void refusedBudgetThrowsAndChargesNoLimiter() {
        FetchBudget limiterBudget = mock(FetchBudget.class);
        when(limiter.member()).thenReturn(limiterBudget);

        assertThatThrownBy(() -> service.personalBests("k1", "g", () -> false))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verify(limiterBudget, never()).tryAcquire();
        verifyNoInteractions(client);
    }

    @Test
    void limiterRefusalThrows() {
        when(limiter.member()).thenReturn(() -> false);

        assertThatThrownBy(() -> service.pendingRuns(BY_TWITCH, "g", Optional.empty(), FetchBudget.UNLIMITED))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verifyNoInteractions(client);
    }

    @Test
    void disabledSourcesAreUnknown() {
        properties.getSpeedrunPbs().setEnabled(false);
        properties.getSpeedrunPending().setEnabled(false);

        assertThat(service.personalBests("k1", "g", FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.pendingRuns(BY_KEY, "g", Optional.empty(), FetchBudget.UNLIMITED)).isEmpty();
        verifyNoInteractions(client);
        assertThat(service.enabled()).isFalse();
    }

    @Test
    void enabledNeedsTheAccountAndPersonalBestSources() {
        assertThat(service.enabled()).isTrue();
        properties.getSpeedrunUser().setEnabled(false);
        assertThat(service.enabled()).isFalse();
    }

    @Test
    void apiKeyOnlyForTheKeysOwnAccount() {
        when(accounts.findById(user.getId())).thenReturn(Optional.of(keyRow("k1", false)));
        when(encryption.decrypt("enc")).thenReturn("secret");

        assertThat(service.apiKey(user, BY_KEY)).contains("secret");
        // found by Twitch: the key is not that account's proof, it is not sent
        assertThat(service.apiKey(user, BY_TWITCH)).isEmpty();
    }

    @Test
    void apiKeyAbsentWhenInvalidReplacedMissingOrUndecryptable() {
        when(accounts.findById(user.getId())).thenReturn(Optional.of(keyRow("k1", true)));
        assertThat(service.apiKey(user, BY_KEY)).isEmpty();

        when(accounts.findById(user.getId())).thenReturn(Optional.of(keyRow("other", false)));
        assertThat(service.apiKey(user, BY_KEY)).isEmpty();

        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        assertThat(service.apiKey(user, BY_KEY)).isEmpty();

        when(accounts.findById(user.getId())).thenReturn(Optional.of(keyRow("k1", false)));
        when(encryption.decrypt("enc")).thenThrow(new IllegalStateException("bad key"));
        assertThat(service.apiKey(user, BY_KEY)).isEmpty();
    }
}
