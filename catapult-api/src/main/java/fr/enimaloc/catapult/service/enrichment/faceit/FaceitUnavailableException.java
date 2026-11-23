package fr.enimaloc.catapult.service.enrichment.faceit;

/**
 * The FACEIT Data API cannot be read right now (down, refusing the keys, rate limited, no key in the pool, a
 * redirect, a garbled or wrong-shaped answer): never "nothing there". The message never carries a key nor a body.
 */
public class FaceitUnavailableException extends RuntimeException {

    public FaceitUnavailableException(String message) {
        super(message);
    }
}
