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
import java.util.List;
import java.util.Optional;

/**
 * OpenCritic critic scores through the shared cache. A game is found by the exact normalised name (a near miss is
 * absent, never a wrong game); the id it resolves to is cached under {@code igdb:<igdbId>} and the game under
 * {@code oc:<id>}. Every upstream call must be accepted by the caller's budget (asked once) and by the daily cap,
 * which is charged one unit per HTTP call, since it protects the RapidAPI quota. The public detail route
 * ({@link #publicById}) must also fit in its own share of half the daily cap, so anonymous callers can never spend
 * what the streamers' game info needs. Without a key in the pool the source is skipped silently.
 */
@Service
@RequiredArgsConstructor
public class OpenCriticService {

    /** The {@link DailyCap} scope of the public detail route's share. */
    static final String PUBLIC_SCOPE = "public";

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

    /** The score of an OpenCritic game id, within the daily cap. */
    public Optional<OpenCriticGame> byId(long id, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        return game(id, both(budget));
    }

    /**
     * The score of an OpenCritic game id for the public detail route: an upstream call must fit in the public
     * share (half the daily cap, counted apart) and in the daily cap itself.
     */
    public Optional<OpenCriticGame> publicById(long id, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        int cap = properties.openCriticDailyCap();
        return game(id, new Both(FetchBudget.once(budget), List.of(
                dailyCap.budget(EnrichmentSource.OPENCRITIC, PUBLIC_SCOPE, cap / 2),
                dailyCap.budget(EnrichmentSource.OPENCRITIC, cap))));
    }

    private Optional<OpenCriticGame> game(long id, FetchBudget budget) {
        return cache.get(EnrichmentSource.OPENCRITIC, "oc:" + id, OpenCriticGame.class, budget,
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
        return new Both(FetchBudget.once(caller), List.of(dailyCap.budget(EnrichmentSource.OPENCRITIC,
                properties.openCriticDailyCap())));
    }

    /**
     * The caller's budget then each cap in order, a later one asked only when every earlier one accepted;
     * {@link #retryAfter()} is that of the budget that refused last.
     */
    private static final class Both implements FetchBudget {
        private final FetchBudget caller;
        private final List<FetchBudget> caps;
        private volatile FetchBudget refused;

        private Both(FetchBudget caller, List<FetchBudget> caps) {
            this.caller = caller;
            this.caps = caps;
        }

        @Override
        public boolean tryAcquire() {
            if (!caller.tryAcquire()) {
                refused = caller;
                return false;
            }
            for (FetchBudget cap : caps) {
                if (!cap.tryAcquire()) {
                    refused = cap;
                    return false;
                }
            }
            refused = null;
            return true;
        }

        @Override
        public Duration retryAfter() {
            FetchBudget last = refused;
            return last != null ? last.retryAfter() : caps.getFirst().retryAfter();
        }
    }
}
