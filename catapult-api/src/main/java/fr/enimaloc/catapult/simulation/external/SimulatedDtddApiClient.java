package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.RealDtddApiClient;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * DoesTheDogDie, in the global {@code dtdd.search} and {@code dtdd.topics} (topics and item) modes. {@code REAL}
 * hands the call to {@link RealDtddApiClient}; otherwise a search finds nothing and no item is known, and a failure
 * answers empty, as the real client does on any failure (rate limit, 404, timeout…).
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = {"catapult.simulation.enabled", "dtdd.enabled"})
public class SimulatedDtddApiClient implements DtddApiClient {

    private final ObjectProvider<RealDtddApiClient> real;
    private final SimulatedCallRunner runner;

    @Override
    public Optional<List<DtddSearchResult>> search(String query, @Nullable String mediaType) {
        return call(SimulatedCalls.DTDD_SEARCH, "GET /v3/items?q=" + query, () -> real().search(query, mediaType),
                () -> Optional.of(List.of()));
    }

    @Override
    public Optional<DtddTopics> fetchTopics(long dtddId) {
        return call(SimulatedCalls.DTDD_TOPICS, "GET /v3/items/" + dtddId + " (topics)",
                () -> real().fetchTopics(dtddId), Optional::empty);
    }

    @Override
    public Optional<DtddItem> item(long dtddId) {
        return call(SimulatedCalls.DTDD_TOPICS, "GET /v3/items/" + dtddId, () -> real().item(dtddId),
                Optional::empty);
    }

    private <T> Optional<T> call(CallKey key, String request, Supplier<Optional<T>> realCall,
                                 Supplier<Optional<T>> simulated) {
        try {
            return runner.run(null, key, request, realCall, simulated, SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Simulated DTDD {} failed: {}", key.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private RealDtddApiClient real() {
        RealDtddApiClient client = real.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("The real DTDD client is not available (dtdd.enabled)");
        }
        return client;
    }
}
