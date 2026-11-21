package fr.enimaloc.catapult.domain.enrichment;

import java.io.Serializable;

/** Composite primary key of {@link GameEnrichmentCacheEntry}. */
public record GameEnrichmentCacheKey(String source, String lookupKey) implements Serializable {
}
