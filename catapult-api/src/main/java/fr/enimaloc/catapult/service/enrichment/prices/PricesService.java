package fr.enimaloc.catapult.service.enrichment.prices;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.GameNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * IsThereAnyDeal prices per country through the shared cache. {@code steam:<appId>} and {@code name:<normalized>}
 * are aliases holding an {@link ItadGame}; {@code itad:<uuid>:<CC>} holds the {@link GamePrices}, stored with every
 * shop ITAD gave. Excluded shops are filtered when reading, so a config change applies at once. Without a key the
 * source is skipped silently. {@code country} must already be validated and upper-cased.
 */
@Service
@RequiredArgsConstructor
public class PricesService {

    private final EnrichmentCache cache;
    private final ItadClient client;
    private final EnrichmentProperties properties;

    public Optional<GamePrices> bySteamAppId(String appId, String country, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        FetchBudget once = FetchBudget.once(budget);
        return cache.get(EnrichmentSource.PRICES, "steam:" + appId, ItadGame.class, once,
                        () -> client.lookupBySteamAppId(appId))
                .flatMap(game -> pricesOf(game, country, once));
    }

    public Optional<GamePrices> byName(String name, String country, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        String normalized = GameNames.normalize(name);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        FetchBudget once = FetchBudget.once(budget);
        return cache.get(EnrichmentSource.PRICES, "name:" + normalized, ItadGame.class, once,
                        () -> client.lookupByTitle(name)
                                .filter(hit -> hit.title() != null
                                        && GameNames.normalize(hit.title()).equals(normalized)))
                .flatMap(game -> pricesOf(game, country, once));
    }

    /**
     * Prices of an ITAD id. On a cold cache the slug of the game page is fetched with the game info endpoint, in the
     * same fetch (and budget unit) as the prices.
     */
    public Optional<GamePrices> byItadId(String itadId, String country, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.PRICES, key(itadId, country), GamePrices.class, budget,
                        () -> client.info(itadId).flatMap(game -> client.prices(game, country)))
                .map(this::withoutExcludedShops);
    }

    private Optional<GamePrices> pricesOf(ItadGame game, String country, FetchBudget budget) {
        return cache.get(EnrichmentSource.PRICES, key(game.id(), country), GamePrices.class, budget,
                        () -> client.prices(game, country))
                .map(this::withoutExcludedShops);
    }

    private static String key(String itadId, String country) {
        return "itad:" + itadId + ":" + country;
    }

    private GamePrices withoutExcludedShops(GamePrices prices) {
        List<String> excluded = properties.excludedShops().stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        if (excluded.isEmpty()) {
            return prices;
        }
        List<GamePrices.Deal> deals = prices.deals().stream()
                .filter(d -> !isExcluded(excluded, d.shopId(), d.shop())).toList();
        GamePrices.HistoryLow low = prices.historyLow();
        if (low != null && isExcluded(excluded, low.shopId(), low.shop())) {
            low = null;
        }
        return new GamePrices(prices.itadId(), prices.country(), prices.currency(),
                deals.isEmpty() ? null : deals.get(0), low, deals, prices.itadUrl());
    }

    /** A shop is excluded by its id or its name, whatever the case. */
    private static boolean isExcluded(List<String> excluded, String shopId, String shop) {
        return (shopId != null && excluded.contains(shopId.toLowerCase(Locale.ROOT)))
                || (shop != null && excluded.contains(shop.toLowerCase(Locale.ROOT)));
    }
}
