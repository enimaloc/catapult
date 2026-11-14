package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LaunchedPlatformsTest {
    static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");

    ApiService api;
    MutableClock clock;
    LaunchedPlatforms launched;

    @BeforeEach
    void setUp() {
        api = mock(ApiService.class);
        clock = new MutableClock(T0);
        launched = new LaunchedPlatforms(api, clock);
    }

    @Test
    void keepsTheListForTheTtl() {
        when(api.launchedPlatforms()).thenReturn(List.of("battlenet"), List.of("battlenet", "ankama"));
        assertThat(launched.ids()).containsExactly("battlenet");
        clock.now = T0.plus(LaunchedPlatforms.TTL).minusSeconds(1);
        assertThat(launched.ids()).containsExactly("battlenet");
        verify(api, times(1)).launchedPlatforms();
        clock.now = T0.plus(LaunchedPlatforms.TTL);
        assertThat(launched.ids()).containsExactly("battlenet", "ankama");
    }

    /** Until an answer was ever obtained, no gated platform shows; afterwards a failure keeps the last answer. */
    @Test
    void showsNoneUntilAnAnswer_thenKeepsTheLastOneWhenTheApiFails() {
        when(api.launchedPlatforms()).thenReturn(null, List.of("ankama"), null);
        assertThat(launched.ids()).isEmpty();
        clock.now = T0.plus(LaunchedPlatforms.RETRY);
        assertThat(launched.ids()).containsExactly("ankama");
        clock.now = clock.now.plus(LaunchedPlatforms.TTL);
        assertThat(launched.ids()).containsExactly("ankama");
    }

    @Test
    void landingPlatforms_addTheLaunchedOnesAfterTheAlwaysShownOnes() {
        when(api.launchedPlatforms()).thenReturn(List.of("ankama", "battlenet"));

        assertThat(SiteCatalog.platforms(launched.ids())).extracting(p -> p.id())
                .containsExactly("minecraft", "steam", "xbox", "battlenet", "ankama");
        assertThat(SiteCatalog.platforms(List.of())).extracting(p -> p.id())
                .containsExactly("minecraft", "steam", "xbox");
    }
}
