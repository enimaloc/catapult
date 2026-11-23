package fr.enimaloc.catapult.service.enrichment.hypixel;

/**
 * Hypixel or Mojang cannot be read right now (down, refusing, rate limited, no key, garbled or wrong-shaped answer,
 * redirected): never "nothing there". The message never carries a key nor a response body.
 */
public class HypixelUnavailableException extends RuntimeException {

    public HypixelUnavailableException(String message) {
        super(message);
    }
}
