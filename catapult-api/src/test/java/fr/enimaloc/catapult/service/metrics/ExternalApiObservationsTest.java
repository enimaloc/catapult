package fr.enimaloc.catapult.service.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalApiObservationsTest {

    private SimpleMeterRegistry meterRegistry;
    private ExternalApiObservations observations;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        observations = new ExternalApiObservations(observationRegistry, meterRegistry);
    }

    @Test
    void observe_success_records_timer_with_success_outcome() {
        String result = observations.observe("igdb", "search_by_name", () -> "ok");

        assertThat(result).isEqualTo("ok");
        Timer timer = meterRegistry.get("catapult.external.api")
                .tag("api", "igdb")
                .tag("operation", "search_by_name")
                .tag("outcome", "success")
                .tag("error", "none")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void observe_error_records_timer_and_rethrows() {
        assertThatThrownBy(() ->
                observations.observe("steam", "get_owned_games", () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        Timer timer = meterRegistry.get("catapult.external.api")
                .tag("api", "steam")
                .tag("operation", "get_owned_games")
                .tag("outcome", "error")
                .tag("error", "IllegalStateException")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void observeRun_wraps_runnable() {
        observations.observeRun("gitlab", "create_issue", () -> { });

        Timer timer = meterRegistry.get("catapult.external.api")
                .tag("api", "gitlab")
                .tag("outcome", "success")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void record_registers_timer_with_correct_tags_and_duration() {
        observations.record("igdb", "find_sources_by_name", "success", "none", 500_000_000L);

        Timer timer = meterRegistry.get("catapult.external.api")
                .tag("api", "igdb")
                .tag("operation", "find_sources_by_name")
                .tag("outcome", "success")
                .tag("error", "none")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isEqualTo(500_000_000.0);
    }
}
