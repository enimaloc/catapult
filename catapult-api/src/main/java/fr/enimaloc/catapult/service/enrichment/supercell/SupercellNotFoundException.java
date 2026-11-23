package fr.enimaloc.catapult.service.enrichment.supercell;

/** Supercell answered its JSON 404 ({@code {"reason":"notFound"}}): no player has this tag in that game. */
public class SupercellNotFoundException extends RuntimeException {

    public SupercellNotFoundException(String message) {
        super(message);
    }
}
