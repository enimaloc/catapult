package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastStateTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
    private final BroadcastState state = new BroadcastState(clock);
    private final Instant startsAt = clock.now.plus(Duration.ofHours(1));

    private static Map<String, Object> scheduled(Instant startsAt, String message) {
        return Map.of("startsAt", startsAt.toString(), "durationMinutes", 30, "message", message);
    }

    @Test
    void remembersAScheduledMaintenance() {
        Map<String, Object> data = scheduled(startsAt, "m");
        state.accept("maintenance.scheduled", data);

        assertThat(state.maintenance()).contains(data);
    }

    @Test
    void acceptsInstants_asTheMockPublishesThem_andKeepsTheIsoString() {
        state.accept("maintenance.scheduled", Map.of("startsAt", startsAt, "durationMinutes", 30, "message", "m"));

        assertThat(state.maintenance()).contains(scheduled(startsAt, "m"));
        assertThat(state.maintenance().orElseThrow().get("startsAt")).isEqualTo(startsAt.toString());

        state.accept("maintenance.cancelled", Map.of("originalStartsAt", startsAt));
        assertThat(state.maintenance()).isEmpty();
    }

    @Test
    void aNewerScheduleReplacesIt() {
        state.accept("maintenance.scheduled", scheduled(startsAt, "m"));
        Map<String, Object> newer = scheduled(startsAt.plus(Duration.ofHours(2)), "later");
        state.accept("maintenance.scheduled", newer);

        assertThat(state.maintenance()).contains(newer);
    }

    @Test
    void cancellingThatMaintenanceForgetsIt() {
        state.accept("maintenance.scheduled", scheduled(startsAt, "m"));
        state.accept("maintenance.cancelled", Map.of("originalStartsAt", startsAt.toString(), "reason", "r"));

        assertThat(state.maintenance()).isEmpty();
    }

    @Test
    void cancellingAnotherMaintenanceKeepsIt() {
        state.accept("maintenance.scheduled", scheduled(startsAt, "m"));
        state.accept("maintenance.cancelled",
                Map.of("originalStartsAt", startsAt.plusSeconds(60).toString(), "reason", "r"));

        assertThat(state.maintenance()).isPresent();
    }

    @Test
    void itIsForgottenOnceItsWindowEnded() {
        state.accept("maintenance.scheduled", scheduled(startsAt, "m"));

        clock.now = startsAt.plus(Duration.ofMinutes(29));
        assertThat(state.maintenance()).isPresent();
        clock.now = startsAt.plus(Duration.ofMinutes(30));
        assertThat(state.maintenance()).isEmpty();
    }

    @Test
    void otherBroadcastsChangeNothing() {
        state.accept("alert.info", Map.of("title", "t"));
        assertThat(state.maintenance()).isEmpty();

        Map<String, Object> data = scheduled(startsAt, "m");
        state.accept("maintenance.scheduled", data);
        state.accept("alert.warning", Map.of("title", "t"));
        state.accept("version.deployed", Map.of());
        assertThat(state.maintenance()).contains(data);
    }

    @Test
    void aMalformedScheduleIsIgnored() {
        state.accept("maintenance.scheduled", Map.of("startsAt", "tomorrow", "durationMinutes", 30, "message", "m"));
        state.accept("maintenance.scheduled", Map.of("message", "m"));
        state.accept("maintenance.scheduled", Map.of("startsAt", startsAt.toString(), "durationMinutes", "x"));
        state.accept("maintenance.cancelled", Map.of("originalStartsAt", "nope"));
        state.accept("maintenance.scheduled", null);

        assertThat(state.maintenance()).isEmpty();
    }
}
