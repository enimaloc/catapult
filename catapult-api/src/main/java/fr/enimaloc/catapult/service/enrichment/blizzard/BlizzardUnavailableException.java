package fr.enimaloc.catapult.service.enrichment.blizzard;

/**
 * The Blizzard API cannot be read right now (down, refusing, rate limited, garbled or wrong-shaped answer): never
 * "nothing there". The message never carries a token nor a response body.
 */
public class BlizzardUnavailableException extends RuntimeException {

    public BlizzardUnavailableException(String message) {
        super(message);
    }
}
