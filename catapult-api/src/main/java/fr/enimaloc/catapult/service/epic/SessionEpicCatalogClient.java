package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.service.epic.api.EpicCatalogApi;
import fr.enimaloc.catapult.service.igdb.EpicCatalogClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Asks the Epic catalog with the access token of an already open bot session. It never refreshes a bot itself
 * (a rotation outside the session could lose its refresh token): without an open session it answers nothing.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("epic.enabled")
public class SessionEpicCatalogClient implements EpicCatalogClient {

    /** The most offer ids asked in one catalog request, to keep its URL short. */
    static final int MAX_IDS_PER_REQUEST = 50;

    private final EpicSessionManager sessions;
    private final EpicCatalogApi catalog;

    public SessionEpicCatalogClient(EpicSessionManager sessions, EpicCatalogApi catalog) {
        this.sessions = sessions;
        this.catalog = catalog;
    }

    @Override
    public Map<String, String> priceTiers(Collection<String> offerIds) {
        if (offerIds.isEmpty()) return Map.of();
        Optional<String> token = sessions.anyOpenSessionToken();
        if (token.isEmpty()) {
            log.debug("No open Epic session: {} catalog offers left unchecked", offerIds.size());
            return Map.of();
        }
        List<String> ids = List.copyOf(offerIds);
        Map<String, String> tiers = new HashMap<>();
        try {
            for (int i = 0; i < ids.size(); i += MAX_IDS_PER_REQUEST) {
                tiers.putAll(catalog.priceTiers(token.get(), ids.subList(i, Math.min(i + MAX_IDS_PER_REQUEST, ids.size()))));
            }
        } catch (RuntimeException e) {
            log.warn("Epic catalog lookup of {} offers failed: {}: {}", ids.size(), e.getClass().getSimpleName(),
                    e.getMessage());
            return Map.of();
        }
        return tiers;
    }
}
