package fr.enimaloc.catapult.repository.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddTopicsCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DtddTopicsCacheRepository extends JpaRepository<DtddTopicsCache, Long> {

    /** The cached topics whose title's ID starts with {@code q}, or whose cached game's name contains it. */
    @Query("SELECT t FROM DtddTopicsCache t WHERE CAST(t.dtddId AS string) LIKE CONCAT(:q, '%') "
            + "OR t.dtddId IN (SELECT g.dtddId FROM DtddGameCache g WHERE LOWER(g.name) LIKE CONCAT('%', :q, '%'))")
    Page<DtddTopicsCache> search(@Param("q") String q, Pageable pageable);

    long countByFetchedAtBefore(Instant before);

    /**
     * The severity levels of these topics (lower-cased names) as the last cached title having them gives them: each
     * row its lower-cased name and its levels as a JSON array.
     */
    @Query(value = "SELECT DISTINCT ON (lower(s.key)) lower(s.key), CAST(s.value -> 'levels' AS text) "
            + "FROM dtdd_topics_cache t, jsonb_each(t.severities) s "
            + "WHERE t.severities IS NOT NULL AND jsonb_typeof(s.value) = 'object' AND lower(s.key) IN (:lowered) "
            + "ORDER BY lower(s.key), t.fetched_at DESC", nativeQuery = true)
    List<Object[]> findLevelsOf(@Param("lowered") java.util.Collection<String> lowered);

    /** Each title's ID and when its topics were fetched, the oldest first: what a bulk refetch goes through. */
    @Query("SELECT t.dtddId, t.fetchedAt FROM DtddTopicsCache t ORDER BY t.fetchedAt")
    List<Object[]> findAllAges();
}
