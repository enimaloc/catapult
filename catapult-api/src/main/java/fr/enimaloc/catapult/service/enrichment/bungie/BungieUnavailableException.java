package fr.enimaloc.catapult.service.enrichment.bungie;

/**
 * The Bungie.net API cannot be read right now (down, in maintenance, refusing, throttling, garbled, redirected or
 * wrong-shaped answer, no application set): never "nothing there". The message never carries a token, a key nor a
 * response body.
 */
public class BungieUnavailableException extends RuntimeException {

    public BungieUnavailableException(String message) {
        super(message);
    }
}
