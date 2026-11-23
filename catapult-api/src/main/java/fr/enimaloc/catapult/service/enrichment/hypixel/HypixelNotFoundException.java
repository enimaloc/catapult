package fr.enimaloc.catapult.service.enrichment.hypixel;

/**
 * A recognised "not found": Hypixel answered {@code {"success":true,"player":null}} (the UUID never joined the
 * server), or Mojang its JSON 404 with an {@code errorMessage} (no profile of that name).
 */
public class HypixelNotFoundException extends RuntimeException {

    public HypixelNotFoundException(String message) {
        super(message);
    }
}
