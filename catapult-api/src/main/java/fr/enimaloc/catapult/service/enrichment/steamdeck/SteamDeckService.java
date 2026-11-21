package fr.enimaloc.catapult.service.enrichment.steamdeck;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Steam Deck compatibility of a Steam app, read through the shared enrichment cache. */
@Service
@RequiredArgsConstructor
public class SteamDeckService {

    private final SteamDeckClient client;
    private final EnrichmentCache cache;

    /**
     * The Steam Deck compatibility of {@code steamAppId}; empty when the source is disabled or has nothing.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when a fetch is needed
     *                                                                                 but {@code budget} refuses it
     */
    public Optional<SteamDeckCompatibility> compatibility(String steamAppId, FetchBudget budget) {
        return cache.get(EnrichmentSource.STEAMDECK, "steam:" + steamAppId, SteamDeckCompatibility.class, budget,
                () -> client.fetch(steamAppId));
    }
}
