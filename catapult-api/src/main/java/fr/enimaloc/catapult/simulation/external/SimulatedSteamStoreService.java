package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.steam.SteamStoreServiceImpl;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Steam store, in the global {@code steam.store.*} modes ({@code app-details} for a page and what is read from
 * it, {@code ccls}, {@code tw-signals}). {@code REAL} hands the call to {@link SteamStoreServiceImpl}; otherwise the
 * store knows nothing, and a failure answers empty, as the real store does on any failure.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedSteamStoreService implements SteamStoreService {

    private final ObjectProvider<SteamStoreServiceImpl> real;
    private final SimulatedCallRunner runner;

    @Override
    public Map<String, Set<String>> fetchCcls(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();
        return call(SimulatedCalls.STEAM_STORE_CCLS, "GET appdetails " + appIds,
                () -> real().fetchCcls(appIds), Map.of());
    }

    @Override
    public Optional<ResolvedParentApp> resolveEffectiveApp(String appId) {
        return call(SimulatedCalls.STEAM_STORE_APP_DETAILS, "GET appdetails " + appId + " (parent)",
                () -> real().resolveEffectiveApp(appId), Optional.empty());
    }

    @Override
    public Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();
        return call(SimulatedCalls.STEAM_STORE_TW_SIGNALS, "GET appdetails " + appIds + " (descriptors)",
                () -> real().fetchTwSignals(appIds), Map.of());
    }

    @Override
    public Optional<String> fetchDescription(String appId, Locale locale) {
        return call(SimulatedCalls.STEAM_STORE_APP_DETAILS, "GET appdetails " + appId + " (description)",
                () -> real().fetchDescription(appId, locale), Optional.empty());
    }

    @Override
    public Optional<String> fetchIADisclosure(String appId, Locale locale) {
        return call(SimulatedCalls.STEAM_STORE_APP_DETAILS, "GET app/" + appId,
                () -> real().fetchIADisclosure(appId, locale), Optional.empty());
    }

    @Override
    public Optional<SteamStorePage> fetchData(String appId, Locale locale, boolean resolveEffectiveParent) {
        return call(SimulatedCalls.STEAM_STORE_APP_DETAILS, "GET appdetails " + appId,
                () -> real().fetchData(appId, locale, resolveEffectiveParent), Optional.empty());
    }

    /** {@code empty} is both the simulated answer and the real store's answer to a failure. */
    private <T> T call(CallKey key, String request, Supplier<T> realCall, T empty) {
        try {
            return runner.run(null, key, request, realCall, () -> empty, SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Simulated Steam store {} failed: {}", key.id(), e.getMessage());
            return empty;
        }
    }

    private SteamStoreServiceImpl real() {
        SteamStoreServiceImpl store = real.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("The real Steam store is not available");
        }
        return store;
    }
}
