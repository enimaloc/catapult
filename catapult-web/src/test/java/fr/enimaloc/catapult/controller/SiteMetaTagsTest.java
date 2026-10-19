package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SiteMetaTagsTest {
    static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");
    static final MetaTagDto A = new MetaTagDto(1, NAME, "theme-color", "#000", T0);
    static final MetaTagDto B = new MetaTagDto(2, NAME, "author", "x", T0);

    ApiService api;
    MutableClock clock;
    SiteMetaTags tags;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        api = mock(ApiService.class);
        ObjectProvider<ApiService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(api);
        clock = new MutableClock(T0);
        tags = new SiteMetaTags(provider, clock);
    }

    @Test
    void keepsTheListASixtySeconds_thenLoadsItAgain() {
        when(api.metaTags()).thenReturn(List.of(A), List.of(A, B));
        assertThat(tags.tags()).containsExactly(A);
        clock.now = T0.plusSeconds(59);
        assertThat(tags.tags()).containsExactly(A);
        verify(api, times(1)).metaTags();
        clock.now = T0.plusSeconds(60);
        assertThat(tags.tags()).containsExactly(A, B);
    }

    @Test
    void aClearMakesTheNextPageLoadIt() {
        when(api.metaTags()).thenReturn(List.of(A), List.of(B));
        tags.tags();
        tags.clear();
        assertThat(tags.tags()).containsExactly(B);
    }

    @Test
    void whenCatapultApiFails_theLastListKeepsShowing_andNoneBeforeTheFirst() {
        when(api.metaTags()).thenReturn(null, List.of(A), (List<MetaTagDto>) null);
        assertThat(tags.tags()).isEmpty();
        clock.now = T0.plus(SiteMetaTags.RETRY);
        assertThat(tags.tags()).containsExactly(A);
        clock.now = clock.now.plus(SiteMetaTags.TTL);
        assertThat(tags.tags()).containsExactly(A);
    }

    @Test
    void aFailedLoad_isOnlyTriedAgainTenSecondsLater() {
        when(api.metaTags()).thenReturn(null, List.of(A));
        tags.tags();
        clock.now = T0.plus(SiteMetaTags.RETRY).minusSeconds(1);
        assertThat(tags.tags()).isEmpty();
        verify(api, times(1)).metaTags();
        clock.now = T0.plus(SiteMetaTags.RETRY);
        assertThat(tags.tags()).containsExactly(A);
    }

    @Test
    void aClear_keepsTheLastListShowing_whenTheNextLoadFails() {
        when(api.metaTags()).thenReturn(List.of(A), (List<MetaTagDto>) null);
        tags.tags();
        tags.clear();
        assertThat(tags.tags()).containsExactly(A);
        verify(api, times(2)).metaTags();
    }

    @Test
    void whileAPageLoadsIt_theOthersGetTheLastListWithoutCallingCatapultApi() throws Exception {
        when(api.metaTags()).thenReturn(List.of(A));
        tags.tags();
        clock.now = T0.plus(SiteMetaTags.TTL);

        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.metaTags()).thenAnswer(invocation -> {
            inCall.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of(B);
        });
        CompletableFuture<List<MetaTagDto>> loader = CompletableFuture.supplyAsync(tags::tags);
        assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(tags.tags()).containsExactly(A);
        release.countDown();
        assertThat(loader.get(5, TimeUnit.SECONDS)).containsExactly(B);
        verify(api, times(2)).metaTags();
    }
}
