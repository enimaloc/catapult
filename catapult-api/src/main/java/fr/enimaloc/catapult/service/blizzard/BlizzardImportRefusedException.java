package fr.enimaloc.catapult.service.blizzard;

/** A World of Warcraft import that is refused: nothing was imported. Its message never holds a token. */
public class BlizzardImportRefusedException extends RuntimeException {

    public enum Reason {
        /** The member has no Battle.net link (or only a removed one). */
        NOT_LINKED,
        /** The consent was given with another Battle.net account than the linked one. */
        OTHER_ACCOUNT
    }

    private final Reason reason;

    public BlizzardImportRefusedException(Reason reason) {
        super("World of Warcraft import refused: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
