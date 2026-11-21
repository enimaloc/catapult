package fr.enimaloc.catapult.service.enrichment.opencritic;

import fr.enimaloc.catapult.service.enrichment.DailyCap;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenCriticServiceTest {

    private static final OpenCriticGame HADES =
            new OpenCriticGame(463, "Hades", 93, 98, "Mighty", 78, "https://opencritic.com/game/463/hades");

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final OpenCriticClient client = mock(OpenCriticClient.class);
    private final DailyCap dailyCap = mock(DailyCap.class);
    private final EnrichmentProperties properties = mock(EnrichmentProperties.class);
    private final OpenCriticService service = new OpenCriticService(cache, client, dailyCap, properties);

    {
        when(client.isConfigured()).thenReturn(true);
        when(properties.openCriticDailyCap()).thenReturn(50);
    }

    /** Behaves like the real cache on a miss: asks the budget, then runs the fetch. */
    @SuppressWarnings("unchecked")
    private <T> void cacheMisses(String key, Class<T> type) {
        when(cache.get(eq(EnrichmentSource.OPENCRITIC), eq(key), eq(type), any(), any())).thenAnswer(inv -> {
            FetchBudget budget = inv.getArgument(3);
            if (!budget.tryAcquire()) {
                throw new UpstreamBudgetExceededException(budget.retryAfter());
            }
            return ((Supplier<Optional<T>>) inv.getArgument(4)).get();
        });
    }

    @Test
    void unconfigured_skipsTheSourceSilently() {
        when(client.isConfigured()).thenReturn(false);

        assertThat(service.byIgdb("1", "Hades", FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.byId(463, FetchBudget.UNLIMITED)).isEmpty();
        verifyNoInteractions(cache);
    }

    @Test
    void nearMissNameIsAbsent() {
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, 50)).thenReturn(FetchBudget.UNLIMITED);
        cacheMisses("igdb:1", OpenCriticRef.class);
        when(client.search("Hades")).thenReturn(List.of(new OpenCriticClient.Hit(1, "Hades II")));

        assertThat(service.byIgdb("1", "Hades", FetchBudget.UNLIMITED)).isEmpty();
        verify(client, never()).game(anyLong());
    }

    @Test
    void exactNormalizedMatchWins() {
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, 50)).thenReturn(FetchBudget.UNLIMITED);
        cacheMisses("igdb:1", OpenCriticRef.class);
        cacheMisses("oc:2", OpenCriticGame.class);
        when(client.search("Hades")).thenReturn(List.of(
                new OpenCriticClient.Hit(1, "Hades II"), new OpenCriticClient.Hit(2, "Hades™")));
        when(client.game(2)).thenReturn(Optional.of(HADES));

        assertThat(service.byIgdb("1", "Hades", FetchBudget.UNLIMITED)).contains(HADES);
    }

    @Test
    void dailyCapExhausted_refusesWhenNothingCached() {
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, 50)).thenReturn(() -> false);
        cacheMisses("oc:463", OpenCriticGame.class);

        assertThatThrownBy(() -> service.byId(463, FetchBudget.UNLIMITED))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verify(client, never()).game(anyLong());
    }

    @Test
    void dailyCapExhausted_servesTheStaleValue() {
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, 50)).thenReturn(() -> false);
        // the real cache serves the stale value when the budget refuses
        when(cache.get(eq(EnrichmentSource.OPENCRITIC), eq("oc:463"), eq(OpenCriticGame.class), any(), any()))
                .thenAnswer(inv -> {
                    FetchBudget budget = inv.getArgument(3);
                    return budget.tryAcquire() ? Optional.empty() : Optional.of(HADES);
                });

        assertThat(service.byId(463, FetchBudget.UNLIMITED)).contains(HADES);
    }

    @Test
    void callerRefusal_doesNotConsumeTheDailyCap() {
        FetchBudget cap = mock(FetchBudget.class);
        when(cap.tryAcquire()).thenReturn(true);
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, 50)).thenReturn(cap);
        cacheMisses("oc:463", OpenCriticGame.class);

        assertThatThrownBy(() -> service.byId(463, () -> false))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verify(cap, never()).tryAcquire();
    }

    @Test
    void searchAndGameCostOneCapUnitEach_callerAskedOnce() {
        FetchBudget cap = mock(FetchBudget.class);
        when(cap.tryAcquire()).thenReturn(true);
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, 50)).thenReturn(cap);
        FetchBudget caller = mock(FetchBudget.class);
        when(caller.tryAcquire()).thenReturn(true);
        cacheMisses("igdb:1", OpenCriticRef.class);
        cacheMisses("oc:463", OpenCriticGame.class);
        when(client.search("Hades")).thenReturn(List.of(new OpenCriticClient.Hit(463, "Hades")));
        when(client.game(463)).thenReturn(Optional.of(HADES));

        assertThat(service.byIgdb("1", "Hades", caller)).contains(HADES);

        verify(cap, times(2)).tryAcquire();
        verify(caller, times(1)).tryAcquire();
    }

    @Test
    void publicRoute_stopsAtHalfTheDailyCap_whileTheAggregateGetsTheRest() {
        OpenCriticService real = new OpenCriticService(cache, client, new DailyCap(), properties);
        when(properties.openCriticDailyCap()).thenReturn(4);
        cacheMisses("oc:463", OpenCriticGame.class);
        when(client.game(463)).thenReturn(Optional.of(HADES));

        assertThat(real.publicById(463, FetchBudget.UNLIMITED)).contains(HADES);
        assertThat(real.publicById(463, FetchBudget.UNLIMITED)).contains(HADES);
        assertThatThrownBy(() -> real.publicById(463, FetchBudget.UNLIMITED))
                .isInstanceOf(UpstreamBudgetExceededException.class);

        assertThat(real.byId(463, FetchBudget.UNLIMITED)).contains(HADES);
        assertThat(real.byId(463, FetchBudget.UNLIMITED)).contains(HADES);
        assertThatThrownBy(() -> real.byId(463, FetchBudget.UNLIMITED))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verify(client, times(4)).game(463);
    }

    @Test
    void publicRoute_alsoNeedsTheGlobalCap() {
        OpenCriticService real = new OpenCriticService(cache, client, new DailyCap(), properties);
        when(properties.openCriticDailyCap()).thenReturn(4);
        cacheMisses("oc:463", OpenCriticGame.class);
        when(client.game(463)).thenReturn(Optional.of(HADES));
        for (int i = 0; i < 4; i++) {
            real.byId(463, FetchBudget.UNLIMITED);
        }

        assertThatThrownBy(() -> real.publicById(463, FetchBudget.UNLIMITED))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verify(client, times(4)).game(463);
    }

    @Test
    void publicRoute_callerRefusal_chargesNoCap() {
        FetchBudget publicCap = mock(FetchBudget.class);
        FetchBudget cap = mock(FetchBudget.class);
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, OpenCriticService.PUBLIC_SCOPE, 25)).thenReturn(publicCap);
        when(dailyCap.budget(EnrichmentSource.OPENCRITIC, 50)).thenReturn(cap);
        cacheMisses("oc:463", OpenCriticGame.class);

        assertThatThrownBy(() -> service.publicById(463, () -> false))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verify(publicCap, never()).tryAcquire();
        verify(cap, never()).tryAcquire();
    }
}
