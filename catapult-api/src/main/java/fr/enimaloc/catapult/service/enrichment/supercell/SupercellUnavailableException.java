package fr.enimaloc.catapult.service.enrichment.supercell;

/**
 * A Supercell API cannot be read right now (down, in maintenance, refusing, rate limited, no usable key, garbled or
 * wrong-shaped answer): never "nothing there". The message never carries a key, an IP address nor a response body.
 */
public class SupercellUnavailableException extends RuntimeException {

    public SupercellUnavailableException(String message) {
        super(message);
    }
}
