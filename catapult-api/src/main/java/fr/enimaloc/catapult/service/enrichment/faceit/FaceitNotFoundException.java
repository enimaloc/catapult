package fr.enimaloc.catapult.service.enrichment.faceit;

/** FACEIT answered its JSON "not found" (a 404 whose {@code errors} say 404): the player or stats do not exist. */
public class FaceitNotFoundException extends RuntimeException {

    public FaceitNotFoundException(String message) {
        super(message);
    }
}
