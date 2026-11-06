package fr.enimaloc.catapult.common.dto.binding;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * One slot of a custom binding's schedule, in local Europe/Paris time. {@code id} is null for a new slot;
 * {@code repeat} is {@code NONE|DAILY|WEEKLY} and {@code days} are {@code DayOfWeek} names.
 */
public record CustomBindingScheduleRequest(UUID id, LocalDateTime startsAt, LocalDateTime endsAt, String repeat,
                                           Set<String> days, LocalDate repeatUntil, boolean paused) {
}
