package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.emergency.EmergencyReportDto;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmergencyStoreTest {

    private final MockEmergencyStore store =
            new MockEmergencyStore(Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void theReports_coverEveryCaseTheTabShows() {
        var reports = store.reports(0);

        assertThat(reports).anyMatch(r -> r.vodUrl() != null && !r.withdrawn());
        assertThat(reports).anyMatch(r -> r.vodUrl() == null);
        assertThat(reports).anyMatch(EmergencyReportDto::withdrawn);
        assertThat(reports).anyMatch(r -> r.contextJson().contains("(60)"));
        assertThat(store.reports(1)).isEmpty();
    }

    @Test
    void aStop_isActive_untilResumed_thenInTheHistory() {
        assertThat(store.ownerStop("NOPE")).isEqualTo(400);
        assertThat(store.ownerStop("STEAM")).isEqualTo(200);
        assertThat(store.stops(true)).extracting(s -> s.provider()).contains("XBOX", "STEAM");

        assertThat(store.ownerResume("STEAM")).isEqualTo(200);

        assertThat(store.stops(true)).extracting(s -> s.provider()).containsExactly("XBOX");
        assertThat(store.stops(false)).anyMatch(s -> s.provider().equals("STEAM") && s.liftedAt() != null);
        assertThat(store.ownerResume("ALL")).isEqualTo(200);
        assertThat(store.stops(true)).isEmpty();
        assertThat(store.status().globalStops()).isEmpty();
    }
}
