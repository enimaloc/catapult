package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.TwitchCategoryCache;
import fr.enimaloc.catapult.repository.TwitchCategoryCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchCategoryServiceTest {

    @Mock private TwitchCategoryCacheRepository cacheRepo;
    @Mock private RestClient restClient;

    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private TwitchCategoryService service;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "twitchClientId",     "test-client");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "test-secret");
        ReflectionTestUtils.setField(service, "cacheTtlHours",      24);

        // Stub app-token fetch used in searchCategories live fallback
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec    = mock(RestClient.RequestBodySpec.class);
        doReturn(postSpec).when(restClient).post();
        doReturn(bodySpec).when(postSpec).uri(anyString());
        doReturn(responseSpec).when(bodySpec).retrieve();
        doReturn(Map.of("access_token", "app-token", "expires_in", 3600))
            .when(responseSpec).body(Map.class);

        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();

        // Prime the app token so tests that override responseSpec don't break token fetch
        service.getOrRefreshAppToken();

        // Default prewarmMode for existing TOP tests
        ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.TOP);
    }

    @Test
    void searchCategories_hitsCacheWhenFresh() {
        TwitchCategoryCache cached = new TwitchCategoryCache("123", "Zelda", "https://img/zelda.jpg");
        when(cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                eq("zelda"), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(cached));

        List<TwitchService.TwitchCategory> results = service.searchCategories("zelda");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("123");
        assertThat(results.get(0).name()).isEqualTo("Zelda");
        assertThat(results.get(0).boxArtUrl()).isEqualTo("https://img/zelda.jpg");
        verify(restClient, never()).get(); // no live call
    }

    @Test
    void searchCategories_callsLiveWhenCacheEmpty() {
        when(cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                any(), any(), any())).thenReturn(List.of());

        doReturn(Map.of("data", List.of(
            Map.of("id", "456", "name", "Fortnite", "box_art_url", "https://img/fn.jpg")
        ))).when(responseSpec).body(Map.class);

        List<TwitchService.TwitchCategory> results = service.searchCategories("fortnite");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("456");
        assertThat(results.get(0).boxArtUrl()).isEqualTo("https://img/fn.jpg");
        verify(cacheRepo).saveAll(anyList());
    }

    @Test
    void searchCategories_returnEmptyWhenLiveUnavailableAndCacheMiss() {
        when(cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                any(), any(), any())).thenReturn(List.of());
        doReturn(null).when(responseSpec).body(Map.class);

        List<TwitchService.TwitchCategory> results = service.searchCategories("anything");

        assertThat(results).isEmpty();
    }

    @Test
    void prewarmCategoryCache_fetchesBatchesAndStopsOnEmpty() {
        // First page returns 2 games with a cursor; second page returns empty data → stop
        doReturn(Map.of(
            "data", List.of(
                Map.of("id", "1", "name", "Game A", "box_art_url", "https://img/a.jpg"),
                Map.of("id", "2", "name", "Game B", "box_art_url", "https://img/b.jpg")
            ),
            "pagination", Map.of("cursor", "abc123")
        )).doReturn(Map.of("data", List.of(), "pagination", Map.of()))
           .when(responseSpec).body(Map.class);

        service.prewarmCategoryCache();

        verify(cacheRepo, times(1)).saveAll(argThat(list ->
            ((List<?>) list).size() == 2));
        verify(cacheRepo, times(1)).saveAll(anyList());
    }

    @Test
    void prewarmCategoryCache_skipsWhenClientIdBlank() {
        ReflectionTestUtils.setField(service, "twitchClientId", "");

        service.prewarmCategoryCache();

        verifyNoInteractions(cacheRepo);
        verify(restClient, never()).get();
    }

    @Test
    void prewarmCategoryCache_skipsWhenModeIsNone() {
        ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.NONE);

        service.prewarmCategoryCache();

        verifyNoInteractions(cacheRepo);
        verify(restClient, never()).get();
    }

    @Test
    void prewarmCategoryCache_sweepStopsOnFirstEmptyBatch() {
        ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.SWEEP);

        // First sweep batch returns 1 game; second returns empty → stop
        doReturn(Map.of(
            "data", List.of(
                Map.of("id", "743", "name", "Chess", "box_art_url", "https://img/chess.jpg", "igdb_id", "7")
            )
        )).doReturn(Map.of("data", List.of()))
           .when(responseSpec).body(Map.class);

        service.prewarmCategoryCache();

        verify(cacheRepo, times(1)).saveAll(argThat(list -> {
            List<TwitchCategoryCache> l = (List<TwitchCategoryCache>) list;
            return l.size() == 1 && "743".equals(l.get(0).getId()) && "7".equals(l.get(0).getIgdbId());
        }));
        verify(cacheRepo, times(1)).saveAll(anyList());
    }

    @Test
    void prewarmCategoryCache_sweepPopulatesIgdbId() {
        ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.SWEEP);

        doReturn(Map.of(
            "data", List.of(
                Map.of("id", "33214", "name", "Fortnite", "box_art_url", "https://img/fn.jpg", "igdb_id", "1905")
            )
        )).doReturn(Map.of("data", List.of()))
           .when(responseSpec).body(Map.class);

        service.prewarmCategoryCache();

        verify(cacheRepo, times(1)).saveAll(argThat(list -> {
            List<TwitchCategoryCache> l = (List<TwitchCategoryCache>) list;
            return l.size() == 1 && "1905".equals(l.get(0).getIgdbId());
        }));
    }

    @Test
    void prewarmCategoryCache_bothModeCallsTopGamesThenSweep() {
        ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.BOTH);

        // TOP games: one page (no cursor → loop exits after one batch)
        // SWEEP: one batch with 1 game, then empty → stop
        doReturn(Map.of(
            "data", List.of(
                Map.of("id", "10", "name", "TopGame", "box_art_url", "https://img/top.jpg")
            ),
            "pagination", Map.of()   // no cursor key → cursor stays null → do-while exits
        )).doReturn(Map.of(
            "data", List.of(
                Map.of("id", "200", "name", "SweepGame", "box_art_url", "https://img/sweep.jpg", "igdb_id", "42")
            )
        )).doReturn(Map.of("data", List.of()))
          .when(responseSpec).body(Map.class);

        service.prewarmCategoryCache();

        verify(cacheRepo, times(2)).saveAll(anyList());
    }
}
