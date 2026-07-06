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
 * Instrumentation centralisée des appels aux API externes (Twitch, IGDB, DtDD,
 * Steam, GitLab). Chaque appel produit un timer {@code catapult.external.api}
 * taggé api/operation/outcome/error/status. Basé sur l'Observation API : brancher un
 * TracingObservationHandler suffira plus tard pour produire des spans.
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
     * Exécute {@code call} en l'observant. L'exception éventuelle est
     * repropagée telle quelle après enregistrement.
     *
     * @param api       nom court de l'API externe ({@code twitch}, {@code igdb}, …)
     * @param operation nom court de l'appel ({@code search_by_name}, …) — vocabulaire fermé
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
     * Code HTTP porté par l'exception ({@code 429}, {@code 404}, …), ou
     * {@code none} si l'échec n'est pas une réponse HTTP (timeout, réseau, …).
     */
    public static String statusOf(Throwable e) {
        if (e instanceof RestClientResponseException http) {
            return String.valueOf(http.getStatusCode().value());
        }
        return "none";
    }

    /**
     * Enregistrement manuel pour les méthodes à checked exceptions.
     * Utilise directement le Timer pour conserver la durée réelle.
     */
    public void record(String api, String operation, String outcome, String error, long durationNanos) {
        record(api, operation, outcome, error, "success".equals(outcome) ? "2xx" : "none", durationNanos);
    }

    /** Variante avec code HTTP explicite (clients java.net.http qui connaissent le status). */
    public void record(String api, String operation, String outcome, String error, String status, long durationNanos) {
        Timer.builder(METRIC)
                .tags("api", api, "operation", operation, "outcome", outcome, "error", error, "status", status)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
