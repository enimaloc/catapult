package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SteamGridDbServiceTest {

    private static final SteamGridDbAssets ASSETS = new SteamGridDbAssets(5248, "Dota 2",
            "https://www.steamgriddb.com/game/5248", List.of(), List.of(), List.of(), List.of());

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final SteamGridDbClient client = mock(SteamGridDbClient.class);
    private final SteamGridDbService service = new SteamGridDbService(cache, client);

    @Test
    void bestAccessors_nullWhenEmpty() {
        assertThat(ASSETS.bestGrid()).isNull();
        assertThat(ASSETS.bestHero()).isNull();
        assertThat(ASSETS.bestLogo()).isNull();
        assertThat(ASSETS.bestIcon()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bySteamAppId_usesSteamKey() {
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.of(ASSETS));
        when(client.fetchBySteamAppId("570")).thenReturn(Optional.of(ASSETS));

        assertThat(service.bySteamAppId("570", FetchBudget.UNLIMITED)).contains(ASSETS);

        ArgumentCaptor<Supplier<Optional<SteamGridDbAssets>>> fetch = ArgumentCaptor.forClass(Supplier.class);
        verify(cache).get(eq(EnrichmentSource.STEAMGRIDDB), eq("steam:570"), eq(SteamGridDbAssets.class),
                eq(FetchBudget.UNLIMITED), fetch.capture());
        assertThat(fetch.getValue().get()).contains(ASSETS);
        verify(cache).put(EnrichmentSource.STEAMGRIDDB, "sgdb:5248", ASSETS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bySteamAppId_absent_storesNoAlias() {
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(client.fetchBySteamAppId("1")).thenReturn(Optional.empty());

        service.bySteamAppId("1", FetchBudget.UNLIMITED);

        ArgumentCaptor<Supplier<Optional<SteamGridDbAssets>>> fetch = ArgumentCaptor.forClass(Supplier.class);
        verify(cache).get(any(), any(), any(), any(), fetch.capture());
        assertThat(fetch.getValue().get()).isEmpty();
        verify(cache, never()).put(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void byName_usesNormalisedKey() {
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(client.fetchByName("dota 2")).thenReturn(Optional.of(ASSETS));

        assertThat(service.byName("  Dota   2 ", FetchBudget.UNLIMITED)).isEmpty();

        ArgumentCaptor<Supplier<Optional<SteamGridDbAssets>>> fetch = ArgumentCaptor.forClass(Supplier.class);
        verify(cache).get(eq(EnrichmentSource.STEAMGRIDDB), eq("name:dota 2"), eq(SteamGridDbAssets.class),
                any(), fetch.capture());
        assertThat(fetch.getValue().get()).contains(ASSETS);
        verify(cache).put(EnrichmentSource.STEAMGRIDDB, "sgdb:5248", ASSETS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void byGameId_usesSgdbKey() {
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.of(ASSETS));
        when(client.fetchByGameId(5248, null)).thenReturn(Optional.of(ASSETS));

        assertThat(service.byGameId(5248, FetchBudget.UNLIMITED)).contains(ASSETS);

        ArgumentCaptor<Supplier<Optional<SteamGridDbAssets>>> fetch = ArgumentCaptor.forClass(Supplier.class);
        verify(cache).get(eq(EnrichmentSource.STEAMGRIDDB), eq("sgdb:5248"), eq(SteamGridDbAssets.class),
                any(), fetch.capture());
        assertThat(fetch.getValue().get()).contains(ASSETS);
        verify(cache, never()).put(any(), any(), any());
    }
}
