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
}
