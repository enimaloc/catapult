package fr.enimaloc.catapult.service.binding.schedule;

import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule.Repeat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.util.Optional;

/** Finds the occurrence of a custom binding schedule slot that is running now, or the next one. */
public final class ScheduleOccurrences {

    /** A week of dates always holds a weekly occurrence, so scanning ahead never needs to go further. */
    private static final int LOOKAHEAD_DAYS = 8;

    public record Occurrence(Instant start, Instant end) {}

    private ScheduleOccurrences() {}

    /** The occurrence with {@code start <= now < end}, the most recent one if several overlap. */
    public static Optional<Occurrence> current(CustomBindingSchedule s, Instant now, ZoneId zone) {
        if (s.getRepeat() == Repeat.NONE) {
            Occurrence once = occurrence(s, s.getStartsAt().toLocalDate(), zone);
            return Optional.of(once).filter(o -> !o.start().isAfter(now) && now.isBefore(o.end()));
        }
        LocalDate today = now.atZone(zone).toLocalDate();
        // An occurrence may have started as many days before as its slot spans, plus one for offset changes
        long lookBack = ChronoUnit.DAYS.between(s.getStartsAt().toLocalDate(), s.getEndsAt().toLocalDate()) + 1;
        LocalDate from = today.minusDays(lookBack);
        if (from.isBefore(s.getStartsAt().toLocalDate())) {
            from = s.getStartsAt().toLocalDate();
        }
        Occurrence best = null;
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            Occurrence o = occurrenceOn(s, d, zone);
            if (o != null && !o.start().isAfter(now) && now.isBefore(o.end())
                    && (best == null || o.start().isAfter(best.start()))) {
                best = o;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The first occurrence starting strictly after {@code now}. */
    public static Optional<Occurrence> next(CustomBindingSchedule s, Instant now, ZoneId zone) {
        if (s.getRepeat() == Repeat.NONE) {
            Occurrence once = occurrence(s, s.getStartsAt().toLocalDate(), zone);
            return Optional.of(once).filter(o -> o.start().isAfter(now));
        }
        LocalDate from = now.atZone(zone).toLocalDate().minusDays(1);
        LocalDate first = s.getStartsAt().toLocalDate();
        if (from.isBefore(first)) {
            from = first;
        }
        for (int i = 0; i <= LOOKAHEAD_DAYS; i++) {
            Occurrence o = occurrenceOn(s, from.plusDays(i), zone);
            if (o != null && o.start().isAfter(now)) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }

    public static Duration duration(CustomBindingSchedule s) {
        return Duration.between(s.getStartsAt(), s.getEndsAt());
    }

    /** The occurrence starting on {@code date}, or null when the slot does not apply that day. */
    private static Occurrence occurrenceOn(CustomBindingSchedule s, LocalDate date, ZoneId zone) {
        LocalDate first = s.getStartsAt().toLocalDate();
        if (date.isBefore(first)) {
            return null;
        }
        if (s.getRepeat() == Repeat.NONE) {
            if (!date.equals(first)) {
                return null;
            }
        } else {
            if (s.getRepeatUntil() != null && date.isAfter(s.getRepeatUntil())) {
                return null;
            }
            if (s.getRepeat() == Repeat.WEEKLY && !s.getDays().contains(date.getDayOfWeek())) {
                return null;
            }
        }
        return occurrence(s, date, zone);
    }

    /**
     * The occurrence starting on {@code date}: its start and end are the slot's wall times read in {@code zone}, so
     * one spanning a change of offset ends when the slot says, not a nominal duration after its start.
     */
    private static Occurrence occurrence(CustomBindingSchedule s, LocalDate date, ZoneId zone) {
        Instant start = resolve(date.atTime(s.getStartsAt().toLocalTime()), zone);
        LocalDate endDate = date.plusDays(ChronoUnit.DAYS.between(s.getStartsAt().toLocalDate(),
                s.getEndsAt().toLocalDate()));
        Instant end = resolve(endDate.atTime(s.getEndsAt().toLocalTime()), zone);
        // Wall times so close that a change of offset swallows the slot (01:30 to 02:30 on a fall-back night)
        if (!end.isAfter(start)) {
            end = start.plus(duration(s));
        }
        return new Occurrence(start, end);
    }

    /** Resolves a local time; one falling in a spring-forward gap starts when the gap ends. */
    private static Instant resolve(LocalDateTime local, ZoneId zone) {
        ZoneOffsetTransition gap = zone.getRules().getTransition(local);
        if (gap != null && gap.isGap()) {
            return gap.getInstant();
        }
        return ZonedDateTime.of(local, zone).toInstant();
    }
}
