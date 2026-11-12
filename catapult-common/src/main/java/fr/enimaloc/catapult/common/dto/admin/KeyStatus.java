package fr.enimaloc.catapult.common.dto.admin;

/** A shared API key: its id, masked value, owner, remaining rate-limit block, and what it was used for (or null). */
public record KeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds,
                        KeyUsageDto usage) {

    /** A key without its usage. */
    public KeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {
        this(id, masked, owner, blocked, blockedForSeconds, null);
    }
}
