package fr.enimaloc.catapult.service.enrichment.steamstats;

/** Steam cannot answer now (no key, no rate budget, 429, 5xx, a redirect, an unreadable body): never "absent". */
public class SteamStatsUnavailableException extends RuntimeException {

    public SteamStatsUnavailableException(String message) {
        super(message);
    }
}
