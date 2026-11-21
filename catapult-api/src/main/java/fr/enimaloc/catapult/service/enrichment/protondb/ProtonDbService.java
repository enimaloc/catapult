package fr.enimaloc.catapult.service.enrichment.protondb;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** ProtonDB compatibility summaries, served through the shared enrichment cache. */
@Service
@RequiredArgsConstructor
public class ProtonDbService {

    private final EnrichmentCache cache;
    private final ProtonDbClient client;

    /**
     * The summary for a Steam app; empty when the source is disabled or ProtonDB has nothing.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when a fetch is needed but
     *                                                                                  {@code budget} refuses it
     */
    public Optional<ProtonDbSummary> summary(String steamAppId, FetchBudget budget) {
        return cache.get(EnrichmentSource.PROTONDB, "steam:" + steamAppId, ProtonDbSummary.class, budget,
                () -> client.fetchSummary(steamAppId));
    }
}
