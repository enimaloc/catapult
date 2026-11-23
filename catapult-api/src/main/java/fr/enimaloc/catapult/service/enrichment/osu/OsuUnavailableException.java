package fr.enimaloc.catapult.service.enrichment.osu;

/**
 * The osu! API cannot be read right now (down, refusing the application, throttling, garbled, redirected or
 * wrong-shaped answer, no application set, the player asked less than a minute ago): never "nothing there". The
 * message never carries a token, a secret nor a response body.
 */
public class OsuUnavailableException extends RuntimeException {

    public OsuUnavailableException(String message) {
        super(message);
    }
}
