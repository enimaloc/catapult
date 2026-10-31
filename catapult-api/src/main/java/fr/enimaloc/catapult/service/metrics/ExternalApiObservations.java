package fr.enimaloc.catapult.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Centralised instrumentation of the calls to external APIs (Twitch, IGDB, DtDD,
 * Steam, GitLab). Each call produces a {@code catapult.external.api} timer
 * tagged api/operation/outcome/error/status. Based on the Observation API: plugging in a
 * TracingObservationHandler will later be enough to produce spans.
 */
@Component
public class ExternalApiObservations {

    public static final String METRIC = "catapult.external.api";

    private final ObservationRegistry registry;
    private final MeterRegistry meterRegistry;

    public ExternalApiObservations(ObservationRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Runs {@code call} under observation. Any exception is
     * rethrown as is after being recorded.
     *
     * @param api       short name of the external API ({@code twitch}, {@code igdb}, …)
     * @param operation short name of the call ({@code search_by_name}, …) — closed vocabulary
     */
    public <T> T observe(String api, String operation, Supplier<T> call) {
        Observation observation = Observation.createNotStarted(METRIC, registry)
                .lowCardinalityKeyValue("api", api)
                .lowCardinalityKeyValue("operation", operation)
                .start();
        try {
            T result = call.get();
            observation.lowCardinalityKeyValue("outcome", "success");
            observation.lowCardinalityKeyValue("error", "none");
            observation.lowCardinalityKeyValue("status", "2xx");
            return result;
        } catch (RuntimeException | Error e) {
            observation.lowCardinalityKeyValue("outcome", "error");
            observation.lowCardinalityKeyValue("error", e.getClass().getSimpleName());
            observation.lowCardinalityKeyValue("status", statusOf(e));
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    public void observeRun(String api, String operation, Runnable call) {
        observe(api, operation, () -> {
            call.run();
            return null;
        });
    }

    /**
     * HTTP code carried by the exception ({@code 429}, {@code 404}, …), or
     * {@code none} if the failure is not an HTTP response (timeout, network, …).
     */
    public static String statusOf(Throwable e) {
        if (e instanceof RestClientResponseException http) {
            return String.valueOf(http.getStatusCode().value());
        }
        return "none";
    }

    /**
     * Manual recording for methods that throw checked exceptions.
     * Uses the Timer directly to keep the real duration.
     */
    public void record(String api, String operation, String outcome, String error, long durationNanos) {
        record(api, operation, outcome, error, "success".equals(outcome) ? "2xx" : "none", durationNanos);
    }

    /** Variant with an explicit HTTP code (java.net.http clients that know the status). */
    public void record(String api, String operation, String outcome, String error, String status, long durationNanos) {
        Timer.builder(METRIC)
                .tags("api", api, "operation", operation, "outcome", outcome, "error", error, "status", status)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
