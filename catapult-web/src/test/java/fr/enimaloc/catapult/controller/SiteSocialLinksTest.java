package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SiteSocialLinksTest {
    static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");
    static final SocialLinkDto A = new SocialLinkDto(1, SocialPlatform.DISCORD, null, "https://discord.gg/a", true, 0);
    static final SocialLinkDto B = new SocialLinkDto(2, SocialPlatform.X, "Us", "https://x.com/b", true, 1);

    ApiService api;
    MutableClock clock;
    SiteSocialLinks links;

    @BeforeEach
    void setUp() {
        api = mock(ApiService.class);
        clock = new MutableClock(T0);
        links = new SiteSocialLinks(api, clock, InstanceBus.local());
    }

    @Test
    void keepsTheListForTheTtl() {
        when(api.socialLinks()).thenReturn(List.of(A), List.of(A, B));
        assertThat(links.links()).containsExactly(A);
        clock.now = T0.plusSeconds(59);
        assertThat(links.links()).containsExactly(A);
        verify(api, times(1)).socialLinks();
        clock.now = T0.plusSeconds(60);
        assertThat(links.links()).containsExactly(A, B);
    }

    @Test
    void clearMarksStaleOnEveryInstance() {
        when(api.socialLinks()).thenReturn(List.of(A), List.of(B));
        links.links();
        links.clear();
        assertThat(links.links()).containsExactly(B);
    }

    @Test
    void keepsTheLastListWhenTheApiFails() {
        when(api.socialLinks()).thenReturn(null, List.of(A), (List<SocialLinkDto>) null);
        assertThat(links.links()).isEqualTo(SiteSocialLinks.FALLBACK);
        clock.now = T0.plus(SiteSocialLinks.RETRY);
        assertThat(links.links()).containsExactly(A);
        clock.now = clock.now.plus(SiteSocialLinks.TTL);
        assertThat(links.links()).containsExactly(A);
    }

    @Test
    void fallsBackToGithubWhenNothingWasEverLoaded() {
        when(api.socialLinks()).thenReturn(null);
        assertThat(links.links()).singleElement().satisfies(l -> {
            assertThat(l.id()).isZero();
            assertThat(l.platform()).isEqualTo(SocialPlatform.GITHUB);
            assertThat(l.url()).isEqualTo("https://github.com/enimaloc/catapult");
        });
    }

    @Test
    void anEmptyListTheApiAnswered_isKeptEmpty() {
        when(api.socialLinks()).thenReturn(List.of());
        assertThat(links.links()).isEmpty();
    }

    @Test
    void aFailedLoad_isOnlyTriedAgainTenSecondsLater() {
        when(api.socialLinks()).thenReturn(null, List.of(A));
        links.links();
        clock.now = T0.plus(SiteSocialLinks.RETRY).minusSeconds(1);
        assertThat(links.links()).isEqualTo(SiteSocialLinks.FALLBACK);
        verify(api, times(1)).socialLinks();
        clock.now = T0.plus(SiteSocialLinks.RETRY);
        assertThat(links.links()).containsExactly(A);
    }

    @Test
    void aClear_keepsTheLastListShowing_whenTheNextLoadFails() {
        when(api.socialLinks()).thenReturn(List.of(A), (List<SocialLinkDto>) null);
        links.links();
        links.clear();
        assertThat(links.links()).containsExactly(A);
        verify(api, times(2)).socialLinks();
    }

    @Test
    void whileAPageLoadsIt_theOthersGetTheLastListWithoutCallingCatapultApi() throws Exception {
        when(api.socialLinks()).thenReturn(List.of(A));
        links.links();
        clock.now = T0.plus(SiteSocialLinks.TTL);

        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.socialLinks()).thenAnswer(invocation -> {
            inCall.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of(B);
        });
        CompletableFuture<List<SocialLinkDto>> loader = CompletableFuture.supplyAsync(links::links);
        assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(links.links()).containsExactly(A);
        release.countDown();
        assertThat(loader.get(5, TimeUnit.SECONDS)).containsExactly(B);
        verify(api, times(2)).socialLinks();
    }
}
