package fr.enimaloc.catapult.service.enrichment.riot;

/**
 * The Riot API cannot be read right now (down, refusing, rate limited, no key, garbled or wrong-shaped answer):
 * never "nothing there". The message never carries a key nor a response body.
 */
public class RiotUnavailableException extends RuntimeException {

    public RiotUnavailableException(String message) {
        super(message);
    }
}
