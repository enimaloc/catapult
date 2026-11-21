package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/** SteamGridDB artwork, keyed by Steam app id, normalised name or SteamGridDB game id, through the shared cache. */
@Service
@RequiredArgsConstructor
public class SteamGridDbService {

    private final EnrichmentCache cache;
    private final SteamGridDbClient client;

    /** Artwork for a Steam app; empty when the source is disabled or has nothing. */
    public Optional<SteamGridDbAssets> bySteamAppId(String steamAppId, FetchBudget budget) {
        return cache.get(EnrichmentSource.STEAMGRIDDB, "steam:" + steamAppId, SteamGridDbAssets.class, budget,
                () -> client.fetchBySteamAppId(steamAppId));
    }

    /** Artwork for the best match of a game name. */
    public Optional<SteamGridDbAssets> byName(String name, FetchBudget budget) {
        String normalized = normalize(name);
        return cache.get(EnrichmentSource.STEAMGRIDDB, "name:" + normalized, SteamGridDbAssets.class, budget,
                () -> client.fetchByName(normalized));
    }

    /** Artwork for a SteamGridDB game id. */
    public Optional<SteamGridDbAssets> byGameId(long gameId, FetchBudget budget) {
        return cache.get(EnrichmentSource.STEAMGRIDDB, "sgdb:" + gameId, SteamGridDbAssets.class, budget,
                () -> client.fetchByGameId(gameId, null));
    }

    static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
