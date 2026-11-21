package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpeedrunGameServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SpeedrunGame CELESTE =
            new SpeedrunGame("o1y9j9v6", "Celeste", "Celeste", "celeste", "https://www.speedrun.com/celeste", "c");

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final SpeedrunClient client = mock(SpeedrunClient.class);
    private final SpeedrunRateLimiter limiter = mock(SpeedrunRateLimiter.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new HashMap<>();
    private SpeedrunGameService service;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        when(limiter.member()).thenReturn(FetchBudget.UNLIMITED);
        when(limiter.publicShare()).thenReturn(FetchBudget.UNLIMITED);
        service = new SpeedrunGameService(
                new EnrichmentCache(repository, new EnrichmentProperties(), MAPPER), client, limiter);
    }

    private static SpeedrunGame game(String id, String name, String twitch) {
        return new SpeedrunGame(id, name, twitch, id, "https://www.speedrun.com/" + id, null);
    }

    private static SpeedrunRecords records() {
        return new SpeedrunRecords(CELESTE, List.of(new SpeedrunRecords.CategoryRecord("c1", "Any%", "u",
                new BigDecimal("1484.083"), "ingame", List.of(new SpeedrunRecords.Runner("a", null)),
                LocalDate.of(2026, 10, 18), "r", null)));
    }

    @Test
    void nearMissGameIsAbsent() {
        when(client.searchGames("Celeste")).thenReturn(List.of(
                game("a", "Celeste 64", "Celeste 64"), game("b", "CelesteNES", null)));

        assertThat(service.byName("Celeste", FetchBudget.UNLIMITED)).isEmpty();
        verify(client, never()).records(anyString());
    }

    @Test
    void exactMatchAmongNearMissesIsFound() {
        when(client.searchGames("Celeste")).thenReturn(List.of(
                game("a", "Celeste 64", null), CELESTE, game("b", "CelesteNES", null)));

        assertThat(service.byName("Celeste", FetchBudget.UNLIMITED)).contains(CELESTE);
    }

    @Test
    void twoExactMatchesAreAbsent() {
        when(client.searchGames("Celeste")).thenReturn(List.of(
                game("a", "Celeste", "Celeste"), game("b", "CELESTE™", null)));

        assertThat(service.byName("Celeste", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void matchesOnTwitchName() {
        SpeedrunGame pokemon = game("p", "Pokémon Red/Blue", "Pokémon Red & Blue");
        when(client.searchGames("Pokemon Red Blue")).thenReturn(List.of(pokemon, game("q", "Pokémon Yellow", null)));

        assertThat(service.byName("Pokemon Red Blue", FetchBudget.UNLIMITED)).contains(pokemon);
    }

    @Test
    void gameIsCachedUnderItsNormalizedName() {
        when(client.searchGames("Celeste™")).thenReturn(List.of(CELESTE));

        service.byName("Celeste™", FetchBudget.UNLIMITED);
        service.byName("Celeste™", FetchBudget.UNLIMITED);

        assertThat(store).containsKey("speedrun-game|name:celeste");
        verify(client).searchGames("Celeste™");
    }

    @Test
    void recordsAreCachedByGameId() {
        when(client.records("o1y9j9v6")).thenReturn(Optional.of(records()));

        assertThat(service.records("o1y9j9v6", FetchBudget.UNLIMITED)).contains(records());
        assertThat(service.records("o1y9j9v6", FetchBudget.UNLIMITED)).contains(records());

        assertThat(store).containsKey("speedrun-records|records:o1y9j9v6");
        verify(client).records("o1y9j9v6");
    }

    @Test
    void pausedSourceServesStale() throws Exception {
        store.put("speedrun-records|records:o1y9j9v6", new GameEnrichmentCacheEntry("speedrun-records",
                "records:o1y9j9v6", MAPPER.writeValueAsString(records()), Instant.now().minus(Duration.ofDays(2))));
        when(client.records("o1y9j9v6")).thenThrow(new SpeedrunUnavailableException("paused"));

        assertThat(service.records("o1y9j9v6", FetchBudget.UNLIMITED)).contains(records());
    }

    @Test
    void callerRefusalSkipsTheLimiter() {
        FetchBudget refusing = () -> false;
        FetchBudget limiterBudget = mock(FetchBudget.class);
        when(limiter.member()).thenReturn(limiterBudget);

        assertThatThrownBy(() -> service.records("o1y9j9v6", refusing))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verifyNoInteractions(limiterBudget);
        verifyNoInteractions(client);
    }

    @Test
    void limiterRefusalAnswersItsOwnRetryAfter() {
        FetchBudget full = new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return false;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ofSeconds(42);
            }
        };
        when(limiter.member()).thenReturn(full);

        assertThatThrownBy(() -> service.records("o1y9j9v6", FetchBudget.UNLIMITED))
                .isInstanceOfSatisfying(UpstreamBudgetExceededException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(42)));
    }

    @Test
    void publicRecordsUseThePublicShareAndAskTheIpOnce() {
        when(client.records("o1y9j9v6")).thenReturn(Optional.of(records()));
        FetchBudget publicShare = mock(FetchBudget.class);
        when(publicShare.tryAcquire()).thenReturn(true);
        when(limiter.publicShare()).thenReturn(publicShare);
        FetchBudget ip = mock(FetchBudget.class);
        when(ip.tryAcquire()).thenReturn(true);

        assertThat(service.publicRecords("o1y9j9v6", ip)).contains(records());

        verify(ip).tryAcquire();
        verify(publicShare).tryAcquire();
        verify(limiter, never()).member();
    }

    @Test
    void garbledAnswerIsNeverCachedAsAbsent() {
        when(client.searchGames("Celeste")).thenThrow(new SpeedrunUnavailableException("not JSON"));

        assertThat(service.byName("Celeste", FetchBudget.UNLIMITED)).isEmpty();

        assertThat(store).isEmpty();
    }

    @Test
    void nameWithNothingToMatchIsAbsentWithoutAnyCall() {
        assertThat(service.byName("™", FetchBudget.UNLIMITED)).isEmpty();

        verifyNoInteractions(client);
        assertThat(store).isEmpty();
    }
}
