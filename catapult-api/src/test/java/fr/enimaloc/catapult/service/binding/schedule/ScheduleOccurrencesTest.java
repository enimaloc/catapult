package fr.enimaloc.catapult.service.binding.schedule;

import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule.Repeat;
import fr.enimaloc.catapult.service.binding.schedule.ScheduleOccurrences.Occurrence;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleOccurrencesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static CustomBindingSchedule slot(Repeat repeat, String from, String to) {
        CustomBindingSchedule s = new CustomBindingSchedule();
        s.setRepeat(repeat);
        s.setStartsAt(LocalDateTime.parse(from));
        s.setEndsAt(LocalDateTime.parse(to));
        return s;
    }

    private static Instant at(String offsetDateTime) {
        return OffsetDateTime.parse(offsetDateTime).toInstant();
    }

    @Test
    void oneOffContainsOnlyItsRange() {
        CustomBindingSchedule s = slot(Repeat.NONE, "2026-09-04T18:00", "2026-09-07T00:00");
        assertThat(ScheduleOccurrences.current(s, at("2026-09-05T12:00:00+02:00"), PARIS)).isPresent();
        assertThat(ScheduleOccurrences.current(s, at("2026-09-07T00:00:00+02:00"), PARIS)).isEmpty();
    }

    @Test
    void weeklyOnTuesdayAndThursday() {
        CustomBindingSchedule s = slot(Repeat.WEEKLY, "2026-11-10T20:00", "2026-11-10T23:00");
        s.setDays(Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY));
        Optional<Occurrence> cur = ScheduleOccurrences.current(s, at("2026-11-12T21:00:00+01:00"), PARIS);
        assertThat(cur).isPresent();
        assertThat(cur.get().start()).isEqualTo(at("2026-11-12T20:00:00+01:00"));
        Optional<Occurrence> next = ScheduleOccurrences.next(s, at("2026-11-11T10:00:00+01:00"), PARIS);
        assertThat(next).isPresent();
        assertThat(next.get().start()).isEqualTo(at("2026-11-12T20:00:00+01:00"));
    }

    @Test
    void overnightOccurrenceStillRunsPastMidnight() {
        CustomBindingSchedule s = slot(Repeat.WEEKLY, "2026-11-10T22:00", "2026-11-11T01:00");
        s.setDays(Set.of(DayOfWeek.TUESDAY));
        Optional<Occurrence> cur = ScheduleOccurrences.current(s, at("2026-11-11T00:30:00+01:00"), PARIS);
        assertThat(cur).isPresent();
        assertThat(cur.get().start()).isEqualTo(at("2026-11-10T22:00:00+01:00"));
    }

    @Test
    void repeatUntilIsTheLastStartDate() {
        CustomBindingSchedule s = slot(Repeat.DAILY, "2026-11-10T20:00", "2026-11-10T21:00");
        s.setRepeatUntil(LocalDate.parse("2026-11-12"));
        assertThat(ScheduleOccurrences.next(s, at("2026-11-12T21:00:00+01:00"), PARIS)).isEmpty();
        assertThat(ScheduleOccurrences.next(s, at("2026-11-12T19:00:00+01:00"), PARIS)).isPresent();
    }

    @Test
    void noOccurrenceBeforeTheFirstDate() {
        CustomBindingSchedule s = slot(Repeat.DAILY, "2026-11-10T20:00", "2026-11-10T21:00");
        assertThat(ScheduleOccurrences.current(s, at("2026-11-09T20:30:00+01:00"), PARIS)).isEmpty();
    }

    @Test
    void springForwardGapStartsAtTheFirstValidInstant() {
        CustomBindingSchedule s = slot(Repeat.DAILY, "2026-11-10T02:30", "2026-11-10T03:30");
        Optional<Occurrence> next = ScheduleOccurrences.next(s, at("2027-03-28T00:00:00+01:00"), PARIS);
        assertThat(next).isPresent();
        assertThat(next.get().start()).isEqualTo(at("2027-03-28T03:00:00+02:00"));
    }

    /** Typed 01:00 in a browser at +02:00: it fires at 23:00 UTC the day before. */
    @Test
    void wallTimeIsReadInTheSlotsZone() {
        CustomBindingSchedule s = slot(Repeat.NONE, "2027-07-10T01:00", "2027-07-10T03:00");
        Optional<Occurrence> next = ScheduleOccurrences.next(s, at("2027-07-09T12:00:00Z"), PARIS);
        assertThat(next).map(Occurrence::start).contains(at("2027-07-09T23:00:00Z"));

        Optional<Occurrence> ny = ScheduleOccurrences.next(s, at("2027-07-09T12:00:00Z"),
                ZoneId.of("America/New_York"));
        assertThat(ny).map(Occurrence::start).contains(at("2027-07-10T05:00:00Z"));
    }

    /** 01:00 → 03:00 on the fall-back night: three real hours, ending at 03:00 as the slot says. */
    @Test
    void fallBackEndsAtTheSlotsWallTime() {
        CustomBindingSchedule s = slot(Repeat.DAILY, "2026-10-01T01:00", "2026-10-01T03:00");
        Optional<Occurrence> next = ScheduleOccurrences.next(s, at("2026-10-25T00:00:00+02:00"), PARIS);
        assertThat(next).isPresent();
        assertThat(next.get().end()).isEqualTo(at("2026-10-25T03:00:00+01:00"));
        assertThat(Duration.between(next.get().start(), next.get().end())).isEqualTo(Duration.ofHours(3));
    }

    /** 22:00 → 04:00 across the spring-forward night: ends at 04:00 summer time, after five real hours. */
    @Test
    void springForwardEndsAtTheSlotsWallTime() {
        CustomBindingSchedule s = slot(Repeat.NONE, "2026-03-28T22:00", "2026-03-29T04:00");
        Optional<Occurrence> next = ScheduleOccurrences.next(s, at("2026-03-28T12:00:00+01:00"), PARIS);
        assertThat(next).isPresent();
        assertThat(next.get().end()).isEqualTo(at("2026-03-29T04:00:00+02:00"));
    }

    /** A slot the offset change swallows (02:00 → 02:30 in the skipped hour) keeps its nominal length. */
    @Test
    void aSlotInsideTheSkippedHourKeepsItsNominalLength() {
        CustomBindingSchedule s = slot(Repeat.DAILY, "2027-03-01T02:00", "2027-03-01T02:30");
        Optional<Occurrence> next = ScheduleOccurrences.next(s, at("2027-03-28T00:00:00+01:00"), PARIS);
        assertThat(next).isPresent();
        assertThat(Duration.between(next.get().start(), next.get().end())).isEqualTo(Duration.ofMinutes(30));
    }
}
