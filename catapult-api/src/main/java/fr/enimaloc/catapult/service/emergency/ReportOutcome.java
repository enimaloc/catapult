package fr.enimaloc.catapult.service.emergency;

import java.time.Instant;

/** What a report led to; {@code expiresAt} is null for a stop that never expires. */
public record ReportOutcome(Kind kind, int votes, int threshold, Instant expiresAt) {

    public enum Kind { CHANNEL_STOP, GLOBAL_STOP, GLOBAL_EXTENDED, PROVIDER_NOT_USED }
}
