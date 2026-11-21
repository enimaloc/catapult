package fr.enimaloc.catapult.service.enrichment.steamdeck;

import java.util.Optional;

/** Reads the Steam Deck compatibility report of a Steam app from the Steam store. */
public interface SteamDeckClient {

    /**
     * The compatibility report of {@code steamAppId}; empty when the body is unusable.
     * Transport failures propagate so the caller can fall back to a stale cache entry.
     */
    Optional<SteamDeckCompatibility> fetch(String steamAppId);
}
