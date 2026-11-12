package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitCorrectionDto;
import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MockRateLimitsStoreTest {

    private static final String HOST = "HOST store.steampowered.com";
    private static final String PAGE = "GET store.steampowered.com/app/{id}";

    private final MockRateLimitsStore store =
            new MockRateLimitsStore(Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC));

    private LearnedRateLimitDto row(String id) {
        return store.list().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aCorrection_isKept_andAResetForgetsIt() {
        assertThat(store.correct(HOST, new LearnedRateLimitCorrectionDto(25, 60, 30, "ROUTE"))).isEqualTo(200);
        LearnedRateLimitDto host = row(HOST);
        assertThat(host.limitCalls()).isEqualTo(25);
        assertThat(host.limitCorrected()).isTrue();
        assertThat(host.resumeMs()).isEqualTo(30_000);
        assertThat(host.scope()).isNull();

        assertThat(store.correct(PAGE, new LearnedRateLimitCorrectionDto(null, null, null, "host"))).isEqualTo(200);
        assertThat(row(PAGE).scope()).isEqualTo("HOST");
        assertThat(row(PAGE).scopePinned()).isTrue();
        assertThat(row(PAGE).limitCorrected()).isFalse();

        assertThat(store.reset(PAGE)).isEqualTo(200);
        assertThat(row(PAGE).scopePinned()).isFalse();
        assertThat(row(PAGE).limitCalls()).isNull();
        assertThat(store.reset(HOST)).isEqualTo(200);
        assertThat(row(HOST).scope()).isNull();
    }

    @Test
    void aBadCorrection_orAnUnknownScope_isRefused() {
        assertThat(store.correct(HOST, null)).isEqualTo(400);
        assertThat(store.correct(HOST, new LearnedRateLimitCorrectionDto(5, null, null, null))).isEqualTo(400);
        assertThat(store.correct(HOST, new LearnedRateLimitCorrectionDto(0, 60, null, null))).isEqualTo(400);
        assertThat(store.correct(HOST, new LearnedRateLimitCorrectionDto(5, 0, null, null))).isEqualTo(400);
        assertThat(store.correct(HOST, new LearnedRateLimitCorrectionDto(null, null, 0, null))).isEqualTo(400);
        assertThat(store.correct(PAGE, new LearnedRateLimitCorrectionDto(null, null, null, "nope"))).isEqualTo(400);
        assertThat(store.correct("HOST unknown", new LearnedRateLimitCorrectionDto(null, null, null, null))).isEqualTo(404);
        assertThat(store.reset("HOST unknown")).isEqualTo(404);
    }
}
