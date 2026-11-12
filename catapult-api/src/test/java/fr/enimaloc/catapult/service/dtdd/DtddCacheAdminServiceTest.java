package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.admin.DtddCachesSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicsCacheEntryDto;
import fr.enimaloc.catapult.domain.dtdd.DtddApiKeyEntry;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddTopicsCache;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.dtdd.DtddApiKeyRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddSearchCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The DTDD caches as the admin sees them: paged the oldest first, their stale entries, the keys' quotas. */
class DtddCacheAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final int TTL_HOURS = 168;
    private static final Map<DtddCacheKind, Duration> LIFETIMES = Map.of(DtddCacheKind.TOPICS,
            Duration.ofHours(TTL_HOURS), DtddCacheKind.GAMES, Duration.ofDays(180), DtddCacheKind.SEARCHES,
            Duration.ofDays(30));

    private final DtddTopicsCacheRepository topics = mock(DtddTopicsCacheRepository.class);
    private final DtddGameCacheRepository games = mock(DtddGameCacheRepository.class);
    private final DtddSearchCacheRepository searches = mock(DtddSearchCacheRepository.class);
    private final DtddApiKeyRepository keys = mock(DtddApiKeyRepository.class);
    private final DtddApiClient client = mock(DtddApiClient.class);
    private final DtddApiKeyRotator rotator = mock(DtddApiKeyRotator.class);
    private final DtddCacheRefetcher refetcher = mock(DtddCacheRefetcher.class);
    private final DtddCacheRefetchJob job = mock(DtddCacheRefetchJob.class);
    private final DtddCacheAdminService service = new DtddCacheAdminService(topics, games, searches, keys,
            Optional.of(client), Optional.of(rotator), refetcher, job, LIFETIMES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void summary_countsWhatIsPastTheLifetime_andTellsTheKeysQuotas() {
        when(topics.count()).thenReturn(10L);
        when(topics.countByFetchedAtBefore(NOW.minus(Duration.ofHours(TTL_HOURS)))).thenReturn(3L);
        // Each cache by its own lifetime
        when(games.countByFetchedAtBefore(NOW.minus(Duration.ofDays(180)))).thenReturn(1L);
        when(searches.countBySearchedAtBefore(NOW.minus(Duration.ofDays(30)))).thenReturn(2L);
        when(keys.findByExclusiveFalse()).thenReturn(List.of(new DtddApiKeyEntry("ddd_abcdefghijklmnop"),
                new DtddApiKeyEntry("ddd_qrstuvwxyz012345")));
        when(rotator.getQuotas()).thenReturn(Map.of("ddd_abcdefghijklmnop",
                new DtddApiKeyRotator.Quota(12, 4321, NOW.minusSeconds(5).toEpochMilli())));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of("ddd_qrstuvwxyz012345", NOW.plusSeconds(30).toEpochMilli()));
        when(job.monthlyReserve()).thenReturn(500);
        when(job.status()).thenReturn(DtddRefetchJobDto.IDLE);

        DtddCachesSummaryDto summary = service.summary();

        assertThat(summary.topics()).isEqualTo(10L);
        assertThat(summary.topicsStale()).isEqualTo(3L);
        assertThat(summary.gamesStale()).isEqualTo(1L);
        assertThat(summary.searchesStale()).isEqualTo(2L);
        assertThat(summary.topicsTtlHours()).isEqualTo(TTL_HOURS);
        assertThat(summary.gamesTtlHours()).isEqualTo(180 * 24);
        assertThat(summary.monthlyReserve()).isEqualTo(500);
        assertThat(summary.quotas()).hasSize(2);
        assertThat(summary.quotas().get(0)).satisfies(q -> {
            assertThat(q.maskedKey()).isEqualTo("ddd_…mnop");
            assertThat(q.remainingMonth()).isEqualTo(4321);
            assertThat(q.blockedUntil()).isNull();
        });
        assertThat(summary.quotas().get(1)).satisfies(q -> {
            assertThat(q.remainingMonth()).isNull();
            assertThat(q.blockedUntil()).isEqualTo(NOW.plusSeconds(30));
        });
        // The keys themselves never leave
        assertThat(summary.toString()).doesNotContain("abcdefghijklmnop");
    }

    @Test
    void topics_arePagedTheOldestFirst_withTheirTitlesName_andMarkedStale() {
        DtddTopicsCache old = new DtddTopicsCache(7L, List.of("a dog dies"), List.of(), List.of(),
                NOW.minus(Duration.ofHours(TTL_HOURS + 1)));
        when(topics.search(eq("halo"), any())).thenReturn(new PageImpl<>(List.of(old)));
        when(games.findAllById(List.of(7L))).thenReturn(List.of(
                new DtddGameCache(7L, "Halo", null, null, null, NOW)));

        Page<?> page = service.list(DtddCacheKind.TOPICS, " Halo ", 0, 500);

        assertThat(page.getContent()).singleElement().isEqualTo(new DtddTopicsCacheEntryDto(7L, "Halo",
                List.of("a dog dies"), List.of(), List.of(), old.getFetchedAt(), true));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(topics).search(eq("halo"), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(DtddCacheAdminService.MAX_PAGE_SIZE);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "fetchedAt"));
    }

    @Test
    void refetch_givesTheEntryAsNowCached() {
        when(games.existsById(7L)).thenReturn(true);
        when(refetcher.refetch(client, DtddCacheKind.GAMES, "7")).thenReturn(true);
        when(games.findById(7L)).thenReturn(Optional.of(new DtddGameCache(7L, "Halo", null, "Video Game", null, NOW)));

        Object entry = service.refetch(DtddCacheKind.GAMES, " 7 ");

        assertThat(entry).hasFieldOrPropertyWithValue("name", "Halo").hasFieldOrPropertyWithValue("stale", false);
    }

    @Test
    void refetch_ofASearch_readsItsQueryAsTheCacheKeepsIt() {
        when(searches.existsById("halo infinite")).thenReturn(true);
        when(refetcher.refetch(client, DtddCacheKind.SEARCHES, "halo infinite")).thenReturn(false);

        assertThatThrownBy(() -> service.refetch(DtddCacheKind.SEARCHES, " Halo Infinite "))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("502");
    }

    @Test
    void refetch_ofWhatIsNotCached_isNotFound_andAsksNothing() {
        assertThatThrownBy(() -> service.refetch(DtddCacheKind.TOPICS, "7"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(refetcher);
    }

    @Test
    void refetch_withoutDtdd_isUnavailable() {
        DtddCacheAdminService off = new DtddCacheAdminService(topics, games, searches, keys, Optional.empty(),
                Optional.empty(), refetcher, job, LIFETIMES, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> off.refetch(DtddCacheKind.TOPICS, "7"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThat(off.summary().quotas()).isEmpty();
    }
}
