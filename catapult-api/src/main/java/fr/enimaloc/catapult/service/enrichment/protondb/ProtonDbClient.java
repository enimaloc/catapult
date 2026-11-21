package fr.enimaloc.catapult.service.enrichment.protondb;

import java.util.Optional;

/** Reads ProtonDB's public report summaries. */
public interface ProtonDbClient {

    /**
     * The summary for a Steam app; empty when ProtonDB has none (404) or answers something unreadable.
     * Transport failures and 5xx responses propagate so that callers do not cache them as "absent".
     */
    Optional<ProtonDbSummary> fetchSummary(String steamAppId);
}
