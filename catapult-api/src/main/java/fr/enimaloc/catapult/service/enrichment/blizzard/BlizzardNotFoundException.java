package fr.enimaloc.catapult.service.enrichment.blizzard;

/** Blizzard answered with its recognised JSON "not found" body: the character or account does not exist. */
public class BlizzardNotFoundException extends RuntimeException {

    public BlizzardNotFoundException(String message) {
        super(message);
    }
}
