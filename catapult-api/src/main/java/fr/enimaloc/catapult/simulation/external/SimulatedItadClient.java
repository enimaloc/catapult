package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.prices.GamePrices;
import fr.enimaloc.catapult.service.enrichment.prices.GamePrices.Deal;
import fr.enimaloc.catapult.service.enrichment.prices.ItadClient;
import fr.enimaloc.catapult.service.enrichment.prices.ItadGame;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** IsThereAnyDeal in the mock profile: every game has the same three EUR deals, answered without a key. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedItadClient implements ItadClient {

    private static final String ITAD_ID = "00000000-0000-0000-0000-000000000001";

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Optional<ItadGame> lookupBySteamAppId(String appId) {
        return Optional.of(new ItadGame(ITAD_ID, "simulated-game-" + appId, "Simulated game " + appId));
    }

    @Override
    public Optional<ItadGame> lookupByTitle(String title) {
        return Optional.of(new ItadGame(ITAD_ID, "simulated-game", title));
    }

    @Override
    public Optional<ItadGame> info(String itadId) {
        return Optional.of(new ItadGame(itadId, "simulated-game", "Simulated game"));
    }

    @Override
    public Optional<GamePrices> prices(ItadGame game, String country) {
        Deal steam = deal("steam", "Steam", "9.99", "19.99", 50);
        Deal gog = deal("gog", "GOG", "11.99", "19.99", 40);
        Deal epic = deal("epic", "Epic Games Store", "14.99", "19.99", 25);
        return Optional.of(new GamePrices(game.id(), country, "EUR", steam,
                new GamePrices.HistoryLow("steam", "Steam", new BigDecimal("4.99"), Instant.parse("2026-01-01T00:00:00Z")),
                List.of(steam, gog, epic), "https://isthereanydeal.com/game/" + game.slug() + "/"));
    }

    private static Deal deal(String shopId, String shop, String price, String regular, int cut) {
        return new Deal(shopId, shop, new BigDecimal(price), new BigDecimal(regular), cut,
                "https://example.invalid/" + shopId);
    }
}
