package fr.enimaloc.catapult.service.metrics;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Instrumentation centralisée des appels aux API externes (Twitch, IGDB, DtDD,
 * Steam, GitLab). Chaque appel produit un timer {@code catapult.external.api}
 * taggé api/operation/outcome/error. Basé sur l'Observation API : brancher un
 * TracingObservationHandler suffira plus tard pour produire des spans.
 */
@Component
public class ExternalApiObservations {

    public static final String METRIC = "catapult.external.api";

    private final ObservationRegistry registry;

    public ExternalApiObservations(ObservationRegistry registry) {
        this.registry = registry;
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
            return result;
        } catch (RuntimeException | Error e) {
            observation.lowCardinalityKeyValue("outcome", "error");
            observation.lowCardinalityKeyValue("error", e.getClass().getSimpleName());
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
}
