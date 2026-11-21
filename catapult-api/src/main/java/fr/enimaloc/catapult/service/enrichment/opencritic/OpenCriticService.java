package fr.enimaloc.catapult.service.enrichment.opencritic;

import fr.enimaloc.catapult.service.enrichment.DailyCap;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.GameNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * OpenCritic critic scores through the shared cache. A game is found by the exact normalised name (a near miss is
 * absent, never a wrong game); the id it resolves to is cached under {@code igdb:<igdbId>} and the game under
 * {@code oc:<id>}. Every upstream call must be accepted by the caller's budget (asked once) and by the daily cap,
 * which is charged one unit per HTTP call, since it protects the RapidAPI quota. Without a key in the pool the
 * source is skipped silently.
 */
@Service
@RequiredArgsConstructor
public class OpenCriticService {

    private final EnrichmentCache cache;
    private final OpenCriticClient client;
    private final DailyCap dailyCap;
    private final EnrichmentProperties properties;

    /** The score of the game {@code name}, remembered under its IGDB id; empty when absent or disabled. */
    public Optional<OpenCriticGame> byIgdb(String igdbId, String name, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        FetchBudget both = both(budget);
        return cache.get(EnrichmentSource.OPENCRITIC, "igdb:" + igdbId, OpenCriticRef.class, both,
                        () -> resolve(name))
                .flatMap(ref -> byId(ref.id(), both));
    }

    /** The score of an OpenCritic game id. */
    public Optional<OpenCriticGame> byId(long id, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.OPENCRITIC, "oc:" + id, OpenCriticGame.class, both(budget),
                () -> client.game(id));
    }

    private Optional<OpenCriticRef> resolve(String name) {
        String wanted = GameNames.normalize(name);
        return client.search(name).stream()
                .filter(hit -> GameNames.normalize(hit.name()).equals(wanted))
                .findFirst()
                .map(hit -> new OpenCriticRef(hit.id()));
    }

    /** The caller's budget (asked once) then the daily cap; the cap is not charged when the caller refuses. */
    private FetchBudget both(FetchBudget caller) {
        if (caller instanceof Both existing) {
            return existing;
        }
        return new Both(FetchBudget.once(caller), dailyCap.budget(EnrichmentSource.OPENCRITIC,
                properties.openCriticDailyCap()));
    }

    private static final class Both implements FetchBudget {
        private final FetchBudget caller;
        private final FetchBudget cap;
        private volatile boolean callerRefused;

        private Both(FetchBudget caller, FetchBudget cap) {
            this.caller = caller;
            this.cap = cap;
        }

        @Override
        public boolean tryAcquire() {
            if (!caller.tryAcquire()) {
                callerRefused = true;
                return false;
            }
            callerRefused = false;
            return cap.tryAcquire();
        }

        @Override
        public Duration retryAfter() {
            return callerRefused ? caller.retryAfter() : cap.retryAfter();
        }
    }
}
