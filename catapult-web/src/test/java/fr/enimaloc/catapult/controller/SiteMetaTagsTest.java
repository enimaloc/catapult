package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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

    static final class MutableClock extends Clock {
        Instant now = T0;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        api = mock(ApiService.class);
        ObjectProvider<ApiService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(api);
        clock = new MutableClock();
        tags = new SiteMetaTags(provider, clock);
    }

    @Test
    void keepsTheListASixtySeconds_thenLoadsItAgain() {
        when(api.metaTags()).thenReturn(List.of(A), List.of(A, B));
        assertThat(tags.metaTags()).containsExactly(A);
        clock.now = T0.plusSeconds(59);
        assertThat(tags.metaTags()).containsExactly(A);
        verify(api, times(1)).metaTags();
        clock.now = T0.plusSeconds(60);
        assertThat(tags.metaTags()).containsExactly(A, B);
    }

    @Test
    void aClearMakesTheNextPageLoadIt() {
        when(api.metaTags()).thenReturn(List.of(A), List.of(B));
        tags.metaTags();
        tags.clear();
        assertThat(tags.metaTags()).containsExactly(B);
    }

    @Test
    void whenCatapultApiFails_theLastListKeepsShowing_andNoneBeforeTheFirst() {
        when(api.metaTags()).thenReturn(null, List.of(A), (List<MetaTagDto>) null);
        assertThat(tags.metaTags()).isEmpty();
        assertThat(tags.metaTags()).containsExactly(A);
        clock.now = T0.plusSeconds(61);
        assertThat(tags.metaTags()).containsExactly(A);
    }
}
