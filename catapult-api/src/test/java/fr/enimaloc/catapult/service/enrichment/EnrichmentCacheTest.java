package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrichmentCacheTest {

    record Payload(String name, int hours) {
    }

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String KEY = "steam:42";

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private EnrichmentCache cache;

    @BeforeEach
    void setUp() {
        cache = new EnrichmentCache(repository, properties, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stored(String payload, Duration age) {
        when(repository.findBySourceAndLookupKey("protondb", KEY))
                .thenReturn(Optional.of(new GameEnrichmentCacheEntry("protondb", KEY, payload, NOW.minus(age))));
    }

    private Optional<Payload> get(FetchBudget budget, AtomicInteger fetches, Supplier<Optional<Payload>> fetch) {
        return cache.get(EnrichmentSource.PROTONDB, KEY, Payload.class, budget, () -> {
            fetches.incrementAndGet();
            return fetch.get();
        });
    }

    @Test
    void miss_fetchesStoresAndReturns() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenReturn(Optional.empty());
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("Gold", 12)));

        assertThat(result).contains(new Payload("Gold", 12));
        assertThat(fetches).hasValue(1);
        ArgumentCaptor<GameEnrichmentCacheEntry> saved = ArgumentCaptor.forClass(GameEnrichmentCacheEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo("protondb");
        assertThat(saved.getValue().getLookupKey()).isEqualTo(KEY);
        assertThat(saved.getValue().getPayload()).contains("Gold");
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void fresh_hit_doesNotFetch() {
        stored("{\"name\":\"Gold\",\"hours\":12}", Duration.ofHours(1));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, Optional::empty);

        assertThat(result).contains(new Payload("Gold", 12));
        assertThat(fetches).hasValue(0);
    }

    @Test
    void stale_fetchFails_servesStaleValue() {
        stored("{\"name\":\"Old\",\"hours\":3}", Duration.ofDays(2));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> {
            throw new RuntimeException("upstream down");
        });

        assertThat(result).contains(new Payload("Old", 3));
        assertThat(fetches).hasValue(1);
        verify(repository, never()).save(any());
    }

    @Test
    void knownAbsent_withinAbsentTtl_returnsEmptyWithoutFetch() {
        stored(null, Duration.ofHours(1));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("x", 1)));

        assertThat(result).isEmpty();
        assertThat(fetches).hasValue(0);
    }

    @Test
    void fetchReturnsEmpty_storesKnownAbsent() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenReturn(Optional.empty());
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, Optional::empty);

        assertThat(result).isEmpty();
        ArgumentCaptor<GameEnrichmentCacheEntry> saved = ArgumentCaptor.forClass(GameEnrichmentCacheEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPayload()).isNull();
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void miss_budgetRefused_throwsUpstreamBudgetExceeded() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenReturn(Optional.empty());
        AtomicInteger fetches = new AtomicInteger();

        assertThatThrownBy(() -> get(() -> false, fetches, () -> Optional.of(new Payload("x", 1))))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        assertThat(fetches).hasValue(0);
    }

    /** Two first fetches race: the loser's insert hits the primary key, its fetched value is still answered. */
    @Test
    void concurrentFirstFetch_duplicateKeyOnSave_returnsFetchedValue() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("Gold", 12)));

        assertThat(result).contains(new Payload("Gold", 12));
        assertThat(fetches).hasValue(1);
    }

    @Test
    void put_storesValueAsFresh() {
        cache.put(EnrichmentSource.PROTONDB, "alias:1", new Payload("Gold", 12));

        ArgumentCaptor<GameEnrichmentCacheEntry> saved = ArgumentCaptor.forClass(GameEnrichmentCacheEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getLookupKey()).isEqualTo("alias:1");
        assertThat(saved.getValue().getPayload()).contains("Gold");
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void put_neverStoresAbsentNorIntoADisabledSource() {
        cache.put(EnrichmentSource.PROTONDB, "alias:1", null);
        properties.getProtondb().setEnabled(false);
        cache.put(EnrichmentSource.PROTONDB, "alias:1", new Payload("Gold", 12));

        verify(repository, never()).save(any());
    }

    @Test
    void put_duplicateKey_isTolerated() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        cache.put(EnrichmentSource.PROTONDB, "alias:1", new Payload("Gold", 12));

        verify(repository).save(any());
    }

    @Test
    void disabledSource_returnsEmptyWithoutFetchOrRead() {
        properties.getProtondb().setEnabled(false);
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("x", 1)));

        assertThat(result).isEmpty();
        assertThat(fetches).hasValue(0);
        verify(repository, never()).findBySourceAndLookupKey(anyString(), anyString());
    }

    @Test
    void stale_fetchSucceeds_overwritesEntry() {
        stored("{\"name\":\"Old\",\"hours\":3}", Duration.ofDays(2));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("New", 9)));

        assertThat(result).contains(new Payload("New", 9));
        ArgumentCaptor<GameEnrichmentCacheEntry> saved = ArgumentCaptor.forClass(GameEnrichmentCacheEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPayload()).contains("New");
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void staleKnownAbsent_pastAbsentTtl_fetches() {
        stored(null, Duration.ofDays(2));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("Now", 1)));

        assertThat(result).contains(new Payload("Now", 1));
        assertThat(fetches).hasValue(1);
    }

    @Test
    void stale_budgetRefused_servesStaleValue() {
        stored("{\"name\":\"Old\",\"hours\":3}", Duration.ofDays(2));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(() -> false, fetches, () -> Optional.of(new Payload("x", 1)));

        assertThat(result).contains(new Payload("Old", 3));
        assertThat(fetches).hasValue(0);
    }

    @Test
    void corruptFreshPayload_isTreatedAsMiss() {
        stored("not json", Duration.ofHours(1));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("Fixed", 2)));

        assertThat(result).contains(new Payload("Fixed", 2));
        assertThat(fetches).hasValue(1);
    }

    @Test
    void corruptStalePayload_fetchFails_returnsEmpty() {
        stored("not json", Duration.ofDays(2));
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> {
            throw new RuntimeException("upstream down");
        });

        assertThat(result).isEmpty();
    }

    @Test
    void properties_bindDocumentedKeys() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "catapult.enrichment.protondb.enabled", "false",
                "catapult.enrichment.hltb.ttl", "5d",
                "catapult.enrichment.steamgriddb.ttl", "3d")));

        EnrichmentProperties bound = binder.bind("catapult.enrichment", EnrichmentProperties.class).get();

        assertThat(bound.source(EnrichmentSource.PROTONDB).enabled()).isFalse();
        assertThat(bound.source(EnrichmentSource.HLTB).enabled()).isTrue();
        assertThat(bound.source(EnrichmentSource.HLTB).ttl()).isEqualTo(Duration.ofDays(5));
        assertThat(bound.source(EnrichmentSource.STEAMGRIDDB).ttl()).isEqualTo(Duration.ofDays(3));
        assertThat(bound.source(EnrichmentSource.TRAILER).ttl()).isEqualTo(Duration.ofDays(1));
    }
}
