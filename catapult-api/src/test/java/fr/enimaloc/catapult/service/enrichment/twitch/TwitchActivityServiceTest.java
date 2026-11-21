package fr.enimaloc.catapult.service.enrichment.twitch;

import fr.enimaloc.catapult.domain.twitch.TwitchCategoryCache;
import fr.enimaloc.catapult.repository.twitch.TwitchCategoryCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TwitchActivityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final TwitchCategory GTA = new TwitchCategory("32982", "Grand Theft Auto V");
    private static final TwitchLives LIVES = new TwitchLives(3, 60, false, List.of(
            new TwitchLives.Stream("A", "a", "t", 50, "fr", "https://twitch.tv/a")));
    private static final TwitchClips CLIPS = new TwitchClips(List.of(
            new TwitchClips.Clip("Wow", "A", 9, 20, "u", "th")));

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final TwitchActivityClient client = mock(TwitchActivityClient.class);
    private final TwitchCategoryCacheRepository categories = mock(TwitchCategoryCacheRepository.class);
    private final TwitchActivityService service = new TwitchActivityService(cache, client, categories,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** A cache that is always cold: asks the budget, then runs the fetch. */
    @SuppressWarnings("unchecked")
    private void coldCache() {
        when(cache.get(any(EnrichmentSource.class), anyString(), any(Class.class), any(FetchBudget.class), any()))
                .thenAnswer(inv -> {
                    if (!((FetchBudget) inv.getArgument(3)).tryAcquire()) {
                        throw new IllegalStateException("refused");
                    }
                    return ((Supplier<Optional<?>>) inv.getArgument(4)).get();
                });
    }

    private void twitchHasEverything() {
        when(client.isConfigured()).thenReturn(true);
        when(client.categoryByIgdbId("1942")).thenReturn(Optional.of(GTA));
        when(client.lives(eq("32982"), any())).thenReturn(LIVES);
        when(client.clips(eq("32982"), any())).thenReturn(CLIPS);
    }

    @Test
    void oneRefreshChargesOnce() {
        twitchHasEverything();
        coldCache();
        AtomicInteger asked = new AtomicInteger();
        FetchBudget counting = () -> {
            asked.incrementAndGet();
            return true;
        };

        Optional<TwitchActivity> activity = service.byIgdbId("1942", null, counting);

        assertThat(activity).isPresent();
        assertThat(asked.get()).isEqualTo(1);
        verify(client).categoryByIgdbId("1942");
        verify(client).lives("32982", null);
        verify(client).clips("32982", NOW.minusSeconds(7 * 24 * 3600));
        assertThat(activity.get().liveCount()).isEqualTo(3);
        assertThat(activity.get().topClips()).isEqualTo(CLIPS.top());
        assertThat(activity.get().categoryName()).isEqualTo("Grand Theft Auto V");
    }

    @Test
    void livesKeyUsesAllWithoutLanguage_andTheLanguageOtherwise() {
        twitchHasEverything();
        coldCache();

        service.byIgdbId("1942", null, FetchBudget.UNLIMITED);
        service.byIgdbId("1942", "fr", FetchBudget.UNLIMITED);

        verify(cache).get(eq(EnrichmentSource.TWITCH_LIVES), eq("live:32982:all"), eq(TwitchLives.class), any(), any());
        verify(cache).get(eq(EnrichmentSource.TWITCH_LIVES), eq("live:32982:fr"), eq(TwitchLives.class), any(), any());
        verify(cache, org.mockito.Mockito.times(2))
                .get(eq(EnrichmentSource.TWITCH_CLIPS), eq("clips:32982"), eq(TwitchClips.class), any(), any());
        verify(client).lives("32982", "fr");
    }

    @Test
    void noCategoryIsEmpty() {
        when(client.isConfigured()).thenReturn(true);
        when(categories.findFirstByIgdbId("1")).thenReturn(Optional.empty());
        when(client.categoryByIgdbId("1")).thenReturn(Optional.empty());
        coldCache();

        assertThat(service.byIgdbId("1", null, FetchBudget.UNLIMITED)).isEmpty();
        verify(client, never()).lives(any(), any());
    }

    @Test
    void zeroLivesIsNotAbsent() {
        when(client.isConfigured()).thenReturn(true);
        when(client.categoryByIgdbId("1942")).thenReturn(Optional.of(GTA));
        when(client.lives(eq("32982"), any())).thenReturn(new TwitchLives(0, 0, false, List.of()));
        when(client.clips(eq("32982"), any())).thenReturn(new TwitchClips(List.of()));
        coldCache();

        Optional<TwitchActivity> activity = service.byIgdbId("1942", null, FetchBudget.UNLIMITED);

        assertThat(activity).isPresent();
        assertThat(activity.get().liveCount()).isZero();
        assertThat(activity.get().topStreams()).isEmpty();
    }

    @Test
    void categoryFromLocalCacheSkipsHelix() {
        twitchHasEverything();
        TwitchCategoryCache row = new TwitchCategoryCache("32982", "Grand Theft Auto V", null);
        when(categories.findFirstByIgdbId("1942")).thenReturn(Optional.of(row));
        coldCache();

        assertThat(service.byIgdbId("1942", null, FetchBudget.UNLIMITED)).isPresent();
        verify(client, never()).categoryByIgdbId(any());
        verify(cache, never()).get(eq(EnrichmentSource.TWITCH_CATEGORY), anyString(), any(), any(), any());
    }

    @Test
    void byCategoryId_usesTheLocalRowThenHelix() {
        twitchHasEverything();
        when(client.categoryById("32982")).thenReturn(Optional.of(GTA));
        coldCache();

        assertThat(service.byCategoryId("32982", null, FetchBudget.UNLIMITED)).isPresent();
        verify(client).categoryById("32982");
        verify(cache).get(eq(EnrichmentSource.TWITCH_CATEGORY), eq("cat:32982"), eq(TwitchCategory.class), any(),
                any());
    }

    @Test
    void notConfigured_skipsEverything() {
        when(client.isConfigured()).thenReturn(false);

        assertThat(service.byIgdbId("1942", null, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.byCategoryId("1", null, FetchBudget.UNLIMITED)).isEmpty();
        verifyNoInteractions(cache, categories);
    }
}
