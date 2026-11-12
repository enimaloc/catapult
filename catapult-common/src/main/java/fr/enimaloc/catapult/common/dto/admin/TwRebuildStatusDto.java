package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/**
 * The TWs' recalculation, one at a time: {@code state} IDLE (none since the start), RUNNING, DONE or CANCELLED;
 * {@code mode} BACKFILL (at start, the bindings without TWs), NON_OVERRIDDEN, UNTOUCHED or FORCE_PINNED;
 * {@code processed}, {@code skipped} and {@code failed} out of {@code total} bindings; {@code waitingUntil} while it
 * waits for the Steam store's rate limit to end.
 */
public record TwRebuildStatusDto(String state, String mode, long total, int processed, int skipped, int failed,
                                 Instant startedAt, Instant finishedAt, Instant waitingUntil) {

    public static final TwRebuildStatusDto IDLE = new TwRebuildStatusDto("IDLE", null, 0, 0, 0, 0, null, null, null);

    /** The bindings gone through so far. */
    public long done() {
        return (long) processed + skipped + failed;
    }
}
