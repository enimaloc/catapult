package fr.enimaloc.catapult.site;

/** What the database said about its role, {@link #UNKNOWN} when it could not be asked. */
public enum DatabaseRole {
    /** A primary: it accepts writes. */
    WRITABLE,
    /** A replica in recovery: it refuses writes. */
    IN_RECOVERY,
    /** The probe failed: a doubt, not a confirmed answer. */
    UNKNOWN
}
