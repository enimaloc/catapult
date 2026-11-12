package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddSearchCache;
import fr.enimaloc.catapult.domain.dtdd.DtddTopicsCache;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddItem;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddSearchResult;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddTopics;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddSearchCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** One cached DTDD entry fetched again: saved as its readers would, untouched when DTDD answers nothing. */
class DtddCacheRefetcherTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private final DtddTopicsCacheRepository topics = mock(DtddTopicsCacheRepository.class);
    private final DtddGameCacheRepository games = mock(DtddGameCacheRepository.class);
    private final DtddSearchCacheRepository searches = mock(DtddSearchCacheRepository.class);
    private final DtddApiClient client = mock(DtddApiClient.class);
    private final DtddCacheRefetcher refetcher =
            new DtddCacheRefetcher(topics, games, searches, Clock.fixed(NOW, ZoneOffset.UTC));

    private static DtddItem item(String name, String poster) {
        return new DtddItem(7, name, null, 2024, 1, "Video Game", 0, null, null, poster, null, null);
    }

    @Test
    void topics_areSavedAnew() {
        when(client.fetchTopics(7L)).thenReturn(Optional.of(
                new DtddTopics(List.of("a dog dies"), List.of("a cat dies"), List.of())));

        assertThat(refetcher.refetch(client, DtddCacheKind.TOPICS, "7")).isTrue();

        ArgumentCaptor<DtddTopicsCache> saved = ArgumentCaptor.forClass(DtddTopicsCache.class);
        verify(topics).save(saved.capture());
        assertThat(saved.getValue().getYesTopics()).containsExactly("a dog dies");
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void aGame_takesTheItemsNameAndType_andKeepsItsPosterWithoutAFullOne() {
        DtddGameCache cached = new DtddGameCache(7L, "Old", "slug", "Movie", "https://poster", NOW.minusSeconds(9));
        when(games.findById(7L)).thenReturn(Optional.of(cached));
        when(client.item(7L)).thenReturn(Optional.of(item("New name", "/relative.jpg")));

        assertThat(refetcher.refetch(client, DtddCacheKind.GAMES, "7")).isTrue();

        assertThat(cached.getName()).isEqualTo("New name");
        assertThat(cached.getMediaType()).isEqualTo("Video Game");
        assertThat(cached.getPosterUrl()).isEqualTo("https://poster");
        assertThat(cached.getSlug()).isEqualTo("slug");
        assertThat(cached.getFetchedAt()).isEqualTo(NOW);
        verify(games).save(cached);
    }

    @Test
    void aSearch_savesTheTitlesItFinds_withTheVideoGamesFilter() {
        when(client.search("halo")).thenReturn(Optional.of(List.of(
                new DtddSearchResult(1, "Halo", "s1", "Video Game", null),
                new DtddSearchResult(2, "Halo 2", "s2", "Video Game", null))));

        assertThat(refetcher.refetch(client, DtddCacheKind.SEARCHES, "halo")).isTrue();

        verify(games, times(2)).save(any(DtddGameCache.class));
        ArgumentCaptor<DtddSearchCache> saved = ArgumentCaptor.forClass(DtddSearchCache.class);
        verify(searches).save(saved.capture());
        assertThat(saved.getValue().getDtddIds()).containsExactly(1L, 2L);
        assertThat(saved.getValue().getSearchedAt()).isEqualTo(NOW);
    }

    @Test
    void dtddAnsweringNothing_changesNothing() {
        when(client.fetchTopics(7L)).thenReturn(Optional.empty());
        when(client.item(7L)).thenReturn(Optional.empty());
        when(client.search("halo")).thenReturn(Optional.empty());

        assertThat(refetcher.refetch(client, DtddCacheKind.TOPICS, "7")).isFalse();
        assertThat(refetcher.refetch(client, DtddCacheKind.GAMES, "7")).isFalse();
        assertThat(refetcher.refetch(client, DtddCacheKind.SEARCHES, "halo")).isFalse();
        verifyNoInteractions(topics, searches);
        verify(games, never()).save(any());
    }

    @Test
    void aTitleKey_mustBeAnId() {
        assertThatThrownBy(() -> refetcher.refetch(client, DtddCacheKind.TOPICS, "halo"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    void topics_keepTheirSeverities() {
        when(client.fetchTopics(7L)).thenReturn(Optional.of(new DtddTopics(List.of("a dog dies"), List.of(), List.of(),
                java.util.Map.of("a dog dies", new DtddApiClient.DtddSeverity(List.of("Implied", "Depicted"),
                        List.of(1, 4), List.of(20, 80), 5, 2)))));

        refetcher.refetch(client, DtddCacheKind.TOPICS, "7");

        ArgumentCaptor<DtddTopicsCache> saved = ArgumentCaptor.forClass(DtddTopicsCache.class);
        verify(topics).save(saved.capture());
        assertThat(saved.getValue().getSeverities()).containsOnlyKeys("a dog dies");
        assertThat(saved.getValue().getSeverities().get("a dog dies").verified()).isEqualTo(2);
        assertThat(saved.getValue().getSeverities().get("a dog dies").percents()).containsExactly(20, 80);
    }
}
