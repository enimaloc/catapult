package fr.enimaloc.catapult.service.enrichment.pcgw;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The lookup keys of the PCGamingWiki cache, and the page id alias that lets the detail route reuse a page. */
class PcGamingWikiServiceTest {

    private static final PcGamingWikiPage PAGE = new PcGamingWikiPage(42L, "Portal 2",
            "https://www.pcgamingwiki.com/wiki/Portal_2", "true", "true", null);

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final PcGamingWikiClient client = mock(PcGamingWikiClient.class);
    private final PcGamingWikiService service = new PcGamingWikiService(client, cache);

    @SuppressWarnings("unchecked")
    private Supplier<Optional<PcGamingWikiPage>> fetchFor(String key) {
        ArgumentCaptor<Supplier<Optional<PcGamingWikiPage>>> fetch = ArgumentCaptor.forClass(Supplier.class);
        verify(cache).get(eq(EnrichmentSource.PCGAMINGWIKI), eq(key), eq(PcGamingWikiPage.class), any(),
                fetch.capture());
        return fetch.getValue();
    }

    @Test
    void bySteamAppId_alsoStoresThePageId() {
        when(client.findBySteamAppId("620")).thenReturn(Optional.of(PAGE));

        service.bySteamAppId("620", FetchBudget.UNLIMITED);

        assertThat(fetchFor("steam:620").get()).contains(PAGE);
        verify(cache).put(EnrichmentSource.PCGAMINGWIKI, "page:42", PAGE);
    }

    @Test
    void byName_usesNormalisedKey_alsoStoresThePageId() {
        when(client.findByName("  Portal   2 ")).thenReturn(Optional.of(PAGE));

        service.byName("  Portal   2 ", FetchBudget.UNLIMITED);

        assertThat(fetchFor("name:portal 2").get()).contains(PAGE);
        verify(cache).put(EnrichmentSource.PCGAMINGWIKI, "page:42", PAGE);
    }

    @Test
    void absent_storesNoAlias() {
        when(client.findBySteamAppId("1")).thenReturn(Optional.empty());

        service.bySteamAppId("1", FetchBudget.UNLIMITED);

        assertThat(fetchFor("steam:1").get()).isEmpty();
        verify(cache, never()).put(any(), any(), any());
    }

    @Test
    void byPageId_usesPageKey_noAlias() {
        when(client.findByPageId(42L)).thenReturn(Optional.of(PAGE));

        service.byPageId(42L, FetchBudget.UNLIMITED);

        assertThat(fetchFor("page:42").get()).contains(PAGE);
        verify(cache, never()).put(any(), any(), any());
    }
}
