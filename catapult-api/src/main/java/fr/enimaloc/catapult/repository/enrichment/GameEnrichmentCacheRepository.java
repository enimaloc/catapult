package fr.enimaloc.catapult.repository.enrichment;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

public interface GameEnrichmentCacheRepository extends JpaRepository<GameEnrichmentCacheEntry, GameEnrichmentCacheKey> {

    Optional<GameEnrichmentCacheEntry> findBySourceAndLookupKey(String source, String lookupKey);

    /** Removes the entries of {@code sources} whose key is exactly {@code exactKey} or starts with {@code prefix}. */
    @Transactional
    @Modifying
    @Query("delete from GameEnrichmentCacheEntry e where e.source in :sources "
            + "and (e.lookupKey = :exactKey or e.lookupKey like :prefix)")
    int deleteByKeyOfSources(@Param("sources") Collection<String> sources, @Param("exactKey") String exactKey,
                             @Param("prefix") String prefix);
}
