package fr.enimaloc.catapult.service.enrichment.rastats;

/** RetroAchievements answered 401: the key of the call is refused. The message never carries the key. */
public class RaKeyRejectedException extends RuntimeException {

    public RaKeyRejectedException() {
        super("RetroAchievements refused the API key");
    }
}
