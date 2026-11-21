package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * SteamGridDB artwork, keyed by Steam app id, normalised name or SteamGridDB game id, through the shared cache. An
 * answer found by Steam app id or name is also stored under its game id, so the detail route reuses it.
 */
@Service
@RequiredArgsConstructor
public class SteamGridDbService {

    private final EnrichmentCache cache;
    private final SteamGridDbClient client;

    /** Artwork for a Steam app; empty when the source is disabled or has nothing. */
    public Optional<SteamGridDbAssets> bySteamAppId(String steamAppId, FetchBudget budget) {
        return cache.get(EnrichmentSource.STEAMGRIDDB, "steam:" + steamAppId, SteamGridDbAssets.class, budget,
                alsoByGameId(() -> client.fetchBySteamAppId(steamAppId)));
    }

    /** Artwork for the best match of a game name. */
    public Optional<SteamGridDbAssets> byName(String name, FetchBudget budget) {
        String normalized = normalize(name);
        return cache.get(EnrichmentSource.STEAMGRIDDB, "name:" + normalized, SteamGridDbAssets.class, budget,
                alsoByGameId(() -> client.fetchByName(normalized)));
    }

    /** Artwork for a SteamGridDB game id. */
    public Optional<SteamGridDbAssets> byGameId(long gameId, FetchBudget budget) {
        return cache.get(EnrichmentSource.STEAMGRIDDB, "sgdb:" + gameId, SteamGridDbAssets.class, budget,
                () -> client.fetchByGameId(gameId, null));
    }

    /** {@code fetch}, whose answer is also stored under {@code sgdb:{gameId}}. */
    private Supplier<Optional<SteamGridDbAssets>> alsoByGameId(Supplier<Optional<SteamGridDbAssets>> fetch) {
        return () -> {
            Optional<SteamGridDbAssets> fetched = fetch.get();
            fetched.ifPresent(assets -> cache.put(EnrichmentSource.STEAMGRIDDB, "sgdb:" + assets.gameId(), assets));
            return fetched;
        };
    }

    static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
