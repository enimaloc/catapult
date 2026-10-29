package fr.enimaloc.catapult.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulatedCallRunnerTest {

    static final CallKey KEY = SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL;

    SimulationRegistry registry = mock(SimulationRegistry.class);
    SimulationJournal journal = new SimulationJournal();
    SimulatedCallRunner runner = new SimulatedCallRunner(registry, journal);
    UUID account = UUID.randomUUID();
    AtomicInteger realCalls = new AtomicInteger();
    AtomicInteger simulatedCalls = new AtomicInteger();

    private void mode(CallSetting setting) {
        when(registry.setting(eq(account), eq(KEY))).thenReturn(setting);
    }

    private String run() {
        return runner.run(account, KEY, "PATCH /channels", () -> {
            realCalls.incrementAndGet();
            return "real";
        }, () -> {
            simulatedCalls.incrementAndGet();
            return "simulated";
        }, SimulatedCallRunner::httpError);
    }

    @Test
    void real_mode_calls_only_the_real_supplier() {
        mode(CallSetting.real());

        assertThat(run()).isEqualTo("real");

        assertThat(realCalls).hasValue(1);
        assertThat(simulatedCalls).hasValue(0);
    }

    @Test
    void ok_mode_never_calls_the_real_supplier() {
        mode(CallSetting.ok());

        assertThat(run()).isEqualTo("simulated");

        assertThat(realCalls).hasValue(0);
        assertThat(simulatedCalls).hasValue(1);
    }

    @Test
    void error_mode_throws_what_errorOf_builds_and_never_calls_real() {
        mode(CallSetting.error(429));

        assertThatThrownBy(this::run).isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(429));
        assertThat(realCalls).hasValue(0);
        assertThat(simulatedCalls).hasValue(0);
    }

    @Test
    void a_5xx_status_becomes_a_server_error() {
        assertThat(SimulatedCallRunner.httpError(503)).isInstanceOf(HttpServerErrorException.class);
        assertThat(SimulatedCallRunner.httpError(401)).isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void timeout_throws_resource_access_exception() {
        mode(CallSetting.timeout());

        assertThatThrownBy(this::run).isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
        assertThat(realCalls).hasValue(0);
    }

    @Test
    void latency_is_applied_before_the_call() {
        mode(CallSetting.ok().withLatency(120));

        long start = System.nanoTime();
        run();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(110);
    }

    @Test
    void every_call_is_journaled_the_real_ones_too() {
        mode(CallSetting.ok().withLatency(5));
        run();
        mode(CallSetting.error(500));
        assertThatThrownBy(this::run).isInstanceOf(RuntimeException.class);
        mode(CallSetting.timeout());
        assertThatThrownBy(this::run).isInstanceOf(RuntimeException.class);
        mode(CallSetting.real());
        run();

        assertThat(journal.recent(account)).extracting(SimulationJournal.Entry::mode)
                .containsExactly("OK", "ERROR", "TIMEOUT", "REAL");
        assertThat(journal.recent(account)).extracting(SimulationJournal.Entry::outcome)
                .containsExactly("ok", "http 500", "timeout", "ok");
        assertThat(journal.recent(account)).extracting(SimulationJournal.Entry::latencyMs)
                .containsExactly(5, 0, 0, 0);
        assertThat(journal.recent(account)).extracting(SimulationJournal.Entry::key)
                .containsOnly(KEY.id());
    }

    @Test
    void a_failed_real_call_is_journaled_with_its_exception() {
        mode(CallSetting.real());

        assertThatThrownBy(() -> runner.run(account, KEY, "PATCH /channels", () -> {
            throw new IllegalStateException("down");
        }, () -> "simulated", SimulatedCallRunner::httpError)).isInstanceOf(IllegalStateException.class);

        assertThat(journal.recent(account)).singleElement().satisfies(entry -> {
            assertThat(entry.mode()).isEqualTo("REAL");
            assertThat(entry.outcome()).isEqualTo("IllegalStateException");
        });
    }

    @Test
    void a_real_failure_is_named_past_its_carriers() {
        assertThat(SimulatedCallRunner.failureOf(new CompletionException(new SocketTimeoutException("slow"))))
                .isEqualTo("SocketTimeoutException");
        assertThat(SimulatedCallRunner.failureOf(new IllegalStateException("down", new RuntimeException("cause"))))
                .isEqualTo("IllegalStateException");
        assertThat(SimulatedCallRunner.failureOf(SimulatedCallRunner.httpError(429))).isEqualTo("http 429");
    }

    @Test
    void a_polled_presence_is_only_journaled_when_its_answer_changes() {
        CallKey presence = SimulatedCalls.STEAM_PRESENCE;
        when(registry.setting(eq(account), eq(presence))).thenReturn(CallSetting.ok());
        String[] answer = {"nothing"};

        for (int poll = 0; poll < 3; poll++) {
            runner.run(account, presence, "presence", () -> "real", () -> answer[0], SimulatedCallRunner::httpError);
        }
        answer[0] = "Valheim";
        runner.run(account, presence, "presence", () -> "real", () -> answer[0], SimulatedCallRunner::httpError);
        runner.run(account, presence, "presence", () -> "real", () -> answer[0], SimulatedCallRunner::httpError);

        assertThat(journal.recent(account)).hasSize(2);
    }

    @Test
    void runVoid_follows_the_same_modes() {
        mode(CallSetting.ok());
        runner.runVoid(account, KEY, "PUT /extensions", realCalls::incrementAndGet, simulatedCalls::incrementAndGet,
                SimulatedCallRunner::httpError);

        assertThat(realCalls).hasValue(0);
        assertThat(simulatedCalls).hasValue(1);
        assertThat(journal.recent(account)).hasSize(1);
    }
}
