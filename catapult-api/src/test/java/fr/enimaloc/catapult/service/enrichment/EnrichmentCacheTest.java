package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

    private Optional<Payload> get(FetchBudget budget, AtomicInteger fetches, java.util.function.Supplier<Optional<Payload>> fetch) {
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

    @Test
    void disabledSource_returnsEmptyWithoutFetchOrRead() {
        EnrichmentProperties.SourceSettings off = new EnrichmentProperties.SourceSettings();
        off.setEnabled(false);
        properties.getSources().put("protondb", off);
        AtomicInteger fetches = new AtomicInteger();

        Optional<Payload> result = get(FetchBudget.UNLIMITED, fetches, () -> Optional.of(new Payload("x", 1)));

        assertThat(result).isEmpty();
        assertThat(fetches).hasValue(0);
        verify(repository, never()).findBySourceAndLookupKey(anyString(), anyString());
    }
}
