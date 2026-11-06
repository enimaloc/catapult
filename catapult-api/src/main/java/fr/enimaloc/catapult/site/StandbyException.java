package fr.enimaloc.catapult.site;

/** Thrown when an action with an external side effect is attempted while this site is on standby. */
public class StandbyException extends RuntimeException {

    public StandbyException(String action) {
        super("This site is on standby, refusing: " + action);
    }
}
