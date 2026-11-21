package fr.enimaloc.catapult.service.enrichment.prices;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PricesServiceTest {

    private static final ItadGame GAME = new ItadGame("uuid-1", "dota-2", "Dota 2");

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final ItadClient client = mock(ItadClient.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final Map<String, GameEnrichmentCacheEntry> store = new HashMap<>();
    private PricesService service;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        when(client.isConfigured()).thenReturn(true);
        service = new PricesService(new EnrichmentCache(repository, properties, JsonMapper.builder().build()),
                client, properties);
    }

    private static GamePrices.Deal deal(String shopId, String price) {
        return new GamePrices.Deal(shopId, shopId, new BigDecimal(price), new BigDecimal("19.99"), 50, "u");
    }

    private static GamePrices prices(String country, GamePrices.HistoryLow low, GamePrices.Deal... deals) {
        List<GamePrices.Deal> list = List.of(deals);
        return new GamePrices("uuid-1", country, "EUR", list.get(0), low, list,
                "https://isthereanydeal.com/game/dota-2/");
    }

    @Test
    void excludedShopNeverSurfaces() {
        properties.getPrices().setExcludedShops(List.of("g2a"));
        when(client.lookupBySteamAppId("570")).thenReturn(Optional.of(GAME));
        when(client.prices(GAME, "FR")).thenReturn(Optional.of(prices("FR",
                new GamePrices.HistoryLow("g2a", "G2A", new BigDecimal("1.00"), Instant.EPOCH),
                deal("g2a", "5.00"), deal("steam", "9.99"))));

        GamePrices p = service.bySteamAppId("570", "FR", FetchBudget.UNLIMITED).orElseThrow();

        assertThat(p.deals()).extracting(GamePrices.Deal::shopId).doesNotContain("g2a");
        assertThat(p.best().shopId()).isEqualTo("steam");
        assertThat(p.historyLow()).isNull();
    }

    @Test
    void exclusionAppliesAtReadTimeNotAtStoreTime() {
        when(client.lookupBySteamAppId("570")).thenReturn(Optional.of(GAME));
        when(client.prices(GAME, "FR")).thenReturn(Optional.of(prices("FR", null,
                deal("g2a", "5.00"), deal("steam", "9.99"))));

        assertThat(service.bySteamAppId("570", "FR", FetchBudget.UNLIMITED).orElseThrow().best().shopId())
                .isEqualTo("g2a");
        properties.getPrices().setExcludedShops(List.of("G2A"));
        assertThat(service.bySteamAppId("570", "FR", FetchBudget.UNLIMITED).orElseThrow().best().shopId())
                .isEqualTo("steam");
        verify(client, times(1)).prices(GAME, "FR");
    }

    @Test
    void allDealsExcluded_bestIsNull() {
        properties.getPrices().setExcludedShops(List.of("g2a"));
        when(client.lookupBySteamAppId("570")).thenReturn(Optional.of(GAME));
        when(client.prices(GAME, "FR")).thenReturn(Optional.of(prices("FR", null, deal("g2a", "5.00"))));

        GamePrices p = service.bySteamAppId("570", "FR", FetchBudget.UNLIMITED).orElseThrow();

        assertThat(p.best()).isNull();
        assertThat(p.deals()).isEmpty();
    }

    @Test
    void titleFallbackNeedsExactNormalizedName() {
        when(client.lookupByTitle("Hades")).thenReturn(Optional.of(new ItadGame("uuid-2", "hades-ii", "Hades II")));

        assertThat(service.byName("Hades", "FR", FetchBudget.UNLIMITED)).isEmpty();
        verify(client, never()).prices(any(), anyString());
    }

    @Test
    void titleMatchingAfterNormalizationIsKept() {
        when(client.lookupByTitle("dota 2")).thenReturn(Optional.of(GAME));
        when(client.prices(GAME, "FR")).thenReturn(Optional.of(prices("FR", null, deal("steam", "9.99"))));

        assertThat(service.byName("dota 2", "FR", FetchBudget.UNLIMITED)).isPresent();
    }

    @Test
    void countryIsPartOfTheKey() {
        when(client.lookupBySteamAppId("570")).thenReturn(Optional.of(GAME));
        when(client.prices(GAME, "FR")).thenReturn(Optional.of(prices("FR", null, deal("steam", "9.99"))));
        when(client.prices(GAME, "US")).thenReturn(Optional.of(prices("US", null, deal("steam", "11.99"))));

        service.bySteamAppId("570", "FR", FetchBudget.UNLIMITED);
        service.bySteamAppId("570", "US", FetchBudget.UNLIMITED);
        service.bySteamAppId("570", "FR", FetchBudget.UNLIMITED);

        verify(client, times(1)).lookupBySteamAppId("570");
        verify(client, times(1)).prices(GAME, "FR");
        verify(client, times(1)).prices(GAME, "US");
    }

    @Test
    void byItadId_coldCache_readsTheSlugFromTheGameInfo() {
        when(client.info("uuid-1")).thenReturn(Optional.of(GAME));
        when(client.prices(GAME, "FR")).thenReturn(Optional.of(prices("FR", null, deal("steam", "9.99"))));

        assertThat(service.byItadId("uuid-1", "FR", FetchBudget.UNLIMITED).orElseThrow().itadUrl())
                .isEqualTo("https://isthereanydeal.com/game/dota-2/");
    }

    @Test
    void unconfiguredClientIsEmptyWithoutCaching() {
        when(client.isConfigured()).thenReturn(false);

        assertThat(service.bySteamAppId("570", "FR", FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.byName("Dota 2", "FR", FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.byItadId("uuid-1", "FR", FetchBudget.UNLIMITED)).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void chainedFetchesAreChargedOneBudgetUnit() {
        when(client.lookupBySteamAppId("570")).thenReturn(Optional.of(GAME));
        when(client.prices(GAME, "FR")).thenReturn(Optional.of(prices("FR", null, deal("steam", "9.99"))));
        int[] asked = {0};

        service.bySteamAppId("570", "FR", () -> {
            asked[0]++;
            return true;
        });

        assertThat(asked[0]).isEqualTo(1);
    }
}
