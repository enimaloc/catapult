package fr.enimaloc.catapult.site;

public interface DatabaseRoleProbe {

    /** Whether the database accepts writes (a primary, not a replica in recovery). */
    boolean writable();
}
