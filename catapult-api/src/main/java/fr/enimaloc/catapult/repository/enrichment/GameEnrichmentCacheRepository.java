package fr.enimaloc.catapult.repository.enrichment;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameEnrichmentCacheRepository extends JpaRepository<GameEnrichmentCacheEntry, GameEnrichmentCacheKey> {

    Optional<GameEnrichmentCacheEntry> findBySourceAndLookupKey(String source, String lookupKey);
}
