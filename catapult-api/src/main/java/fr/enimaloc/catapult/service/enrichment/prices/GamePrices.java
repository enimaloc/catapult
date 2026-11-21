package fr.enimaloc.catapult.service.enrichment.prices;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Current official-store prices of a game in one country. {@code deals} is sorted by price, ascending, and
 * {@code best} is the first of them (null when there is none); {@code historyLow} may be null.
 */
public record GamePrices(String itadId, String country, String currency, Deal best, HistoryLow historyLow,
                         List<Deal> deals, String itadUrl) {

    public record Deal(String shopId, String shop, BigDecimal price, BigDecimal regular, int cut, String url) {
    }

    public record HistoryLow(String shopId, String shop, BigDecimal price, Instant date) {
    }
}
