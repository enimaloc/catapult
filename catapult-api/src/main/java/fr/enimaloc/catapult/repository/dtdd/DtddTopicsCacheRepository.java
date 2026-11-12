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

    /** Each title's ID and when its topics were fetched, the oldest first: what a bulk refetch goes through. */
    @Query("SELECT t.dtddId, t.fetchedAt FROM DtddTopicsCache t ORDER BY t.fetchedAt")
    List<Object[]> findAllAges();
}
