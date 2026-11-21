package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import java.util.Optional;

/** Reads game artwork from the SteamGridDB API v2, authenticated with the shared key pool. */
public interface SteamGridDbClient {

    /**
     * Artwork for a Steam app; empty when SteamGridDB does not know the game (404) or the answer is unreadable.
     * Transport failures, 5xx, a second 429 and {@link SteamGridDbUnavailableException} (no usable key: feature
     * disabled, empty pool) propagate so that callers do not cache them as absent.
     */
    Optional<SteamGridDbAssets> fetchBySteamAppId(String steamAppId);

    /** Artwork for the first autocomplete match of {@code name}; empty when nothing matches. */
    Optional<SteamGridDbAssets> fetchByName(String name);

    /**
     * Artwork for a SteamGridDB game id; {@code name} may be null when unknown, then it is read from SteamGridDB
     * (empty when the id is unknown).
     */
    Optional<SteamGridDbAssets> fetchByGameId(long gameId, String name);
}
