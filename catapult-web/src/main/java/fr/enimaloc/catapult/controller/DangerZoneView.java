package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.UserInfoResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The Settings tab's danger zone: whether the owner's account waits for its deletion, and when that
 * happens. catapult-api stores the time of the request ({@code deletionRequestedAt}), the account
 * going {@code delayDays} later.
 *
 * @param deletesAt null when the account is not pending, or catapult-api gave no request time
 */
public record DangerZoneView(boolean pending, Instant deletesAt, int delayDays) {

    private static final String PENDING_DELETION = "PENDING_DELETION";

    /** @param info the owner's account, null when catapult-api failed (shown as not pending) */
    public static DangerZoneView of(UserInfoResponse info, int delayDays) {
        boolean pending = info != null && PENDING_DELETION.equals(info.status());
        Instant requestedAt = pending ? parse(info.deletionRequestedAt()) : null;
        return new DangerZoneView(pending, requestedAt == null ? null : requestedAt.plus(delayDays, ChronoUnit.DAYS),
                delayDays);
    }

    private static Instant parse(String instant) {
        if (instant == null || instant.isBlank()) return null;
        try {
            return Instant.parse(instant);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
