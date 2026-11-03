package fr.enimaloc.catapult.site;

public interface DatabaseRoleProbe {

    /** Whether the database accepts writes; {@link DatabaseRole#UNKNOWN} when it could not be asked. Never throws. */
    DatabaseRole role();
}
