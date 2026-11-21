package fr.enimaloc.catapult.service.enrichment.prices;

import java.util.Optional;

/** IsThereAnyDeal API. Returns every shop it is given, unfiltered; an upstream failure is an exception. */
public interface ItadClient {

    /** Whether a key is available; without one nothing is sent. */
    boolean isConfigured();

    Optional<ItadGame> lookupBySteamAppId(String appId);

    Optional<ItadGame> lookupByTitle(String title);

    /** The game of an ITAD id (needed for its page slug when only the id is known). */
    Optional<ItadGame> info(String itadId);

    /** Prices and history low of {@code game} in {@code country} (ISO 3166-1 alpha-2, upper case). */
    Optional<GamePrices> prices(ItadGame game, String country);
}
