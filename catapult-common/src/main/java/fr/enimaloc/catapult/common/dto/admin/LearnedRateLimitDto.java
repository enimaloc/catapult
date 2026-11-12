package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/**
 * What Catapult learned of one scope's rate limit from its 429s: a host ({@code method} and {@code path} null: the
 * budget its {@code HOST} routes share) or one of its routes ({@code scope} HOST: in its host's budget, ROUTE: its
 * own limit; {@code scopePinned} when an admin chose it). {@code limitCalls} per {@code limitWindowSeconds} and
 * {@code resumeMs} are what applies (an admin's correction when {@code limitCorrected}/{@code resumeCorrected}, else
 * what was learned; no limit while none was learned). {@code usedCalls}: the calls in that window (a minute without a
 * limit) on the instance that answered; {@code blockedUntil} while it waits after a 429.
 */
public record LearnedRateLimitDto(String id, String host, String method, String path, String scope,
                                  boolean scopePinned, Integer limitCalls, Integer limitWindowSeconds,
                                  boolean limitCorrected, long resumeMs, boolean resumeCorrected,
                                  int rateLimitedCount, Instant lastRateLimitedAt, int usedCalls,
                                  Instant blockedUntil) {

    /** A host's row rather than a route's. */
    public boolean hostScope() {
        return path == null;
    }
}
