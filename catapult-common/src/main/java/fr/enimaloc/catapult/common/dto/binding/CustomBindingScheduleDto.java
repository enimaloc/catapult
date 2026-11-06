package fr.enimaloc.catapult.common.dto.binding;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One slot of a custom binding's schedule, in local Europe/Paris time. {@code days} are {@code DayOfWeek} names,
 * Monday first. {@code suspended}: the owner is no longer entitled to the slot, so it never fires. {@code nextStart}
 * is when the occurrence running now began, else when the next one starts; null when none is left.
 */
public record CustomBindingScheduleDto(
        UUID id,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String repeat,
        List<String> days,
        LocalDate repeatUntil,
        boolean paused,
        boolean suspended,
        Instant nextStart
) {
    /** The time zone slots are typed, stored and shown in. */
    public static final String ZONE = "Europe/Paris";
    /** Twitch's "Special Events" category, the only one a free account may schedule (one-off slots only). */
    public static final String FREE_TWITCH_GAME_ID = "509663";
}
