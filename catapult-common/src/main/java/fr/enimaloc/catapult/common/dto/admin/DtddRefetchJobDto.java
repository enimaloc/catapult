package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/**
 * The DTDD caches' bulk refetch, one at a time: {@code state} IDLE (never run), RUNNING, DONE, STOPPED (it stopped
 * itself, {@code stopReason} QUOTA: every key down to the monthly reserve, NO_KEY: no key at all, ERROR) or
 * CANCELLED; {@code done} entries refetched out of {@code total}, {@code failed} of them DTDD gave nothing for;
 * {@code waitingUntil} while it waits for a key to be usable again.
 */
public record DtddRefetchJobDto(String state, int total, int done, int failed, String stopReason, Instant startedAt,
                                Instant finishedAt, Instant waitingUntil) {

    public static final DtddRefetchJobDto IDLE = new DtddRefetchJobDto("IDLE", 0, 0, 0, null, null, null, null);
}
