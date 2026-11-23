package fr.enimaloc.catapult.service.enrichment.rastats;

/** RetroAchievements cannot answer now (paused, 429, 5xx, a redirect, an unreadable body): never "absent". */
public class RaUnavailableException extends RuntimeException {

    public RaUnavailableException(String message) {
        super(message);
    }
}
