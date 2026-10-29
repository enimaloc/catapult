package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import fr.enimaloc.catapult.service.xbox.XboxStoreServiceImpl;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Locale;
import java.util.Optional;

/**
 * The Microsoft Store catalog, in the global {@code xbox.store.product} mode. {@code REAL} hands the call to
 * {@link XboxStoreServiceImpl}; otherwise no product resolves, and a failure answers empty,
 * as the real store does on any failure.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedXboxStoreService implements XboxStoreService {

    private final ObjectProvider<XboxStoreServiceImpl> real;
    private final SimulatedCallRunner runner;

    @Override
    public Optional<XboxProduct> fetchProduct(String productId, Locale locale) {
        try {
            return runner.run(null, SimulatedCalls.XBOX_STORE_PRODUCT, "GET products/" + productId,
                    () -> real().fetchProduct(productId, locale), Optional::empty, SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Simulated Xbox store product {} failed: {}", productId, e.getMessage());
            return Optional.empty();
        }
    }

    private XboxStoreServiceImpl real() {
        XboxStoreServiceImpl store = real.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("The real Xbox store is not available");
        }
        return store;
    }
}
