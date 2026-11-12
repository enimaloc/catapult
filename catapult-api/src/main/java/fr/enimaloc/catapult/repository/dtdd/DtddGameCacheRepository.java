package fr.enimaloc.catapult.repository.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DtddGameCacheRepository extends JpaRepository<DtddGameCache, Long> {

    @Query("SELECT g FROM DtddGameCache g WHERE LOWER(g.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<DtddGameCache> searchByNameLike(@Param("q") String q, Pageable pageable);

    /** The cached titles whose ID starts with {@code q} or whose name contains it (lower-cased by the caller). */
    @Query("SELECT g FROM DtddGameCache g WHERE CAST(g.dtddId AS string) LIKE CONCAT(:q, '%') "
            + "OR LOWER(g.name) LIKE CONCAT('%', :q, '%')")
    Page<DtddGameCache> search(@Param("q") String q, Pageable pageable);

    long countByFetchedAtBefore(Instant before);

    /** Each title's ID and when it was fetched, the oldest first: what a bulk refetch goes through. */
    @Query("SELECT g.dtddId, g.fetchedAt FROM DtddGameCache g ORDER BY g.fetchedAt")
    List<Object[]> findAllAges();
}
