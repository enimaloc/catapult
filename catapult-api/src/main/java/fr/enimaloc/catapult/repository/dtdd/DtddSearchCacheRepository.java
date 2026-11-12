package fr.enimaloc.catapult.repository.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddSearchCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DtddSearchCacheRepository extends JpaRepository<DtddSearchCache, String> {

    /** The cached searches whose query contains {@code q} (lower-cased by the caller, as the queries are). */
    @Query("SELECT s FROM DtddSearchCache s WHERE s.queryNormalized LIKE CONCAT('%', :q, '%')")
    Page<DtddSearchCache> search(@Param("q") String q, Pageable pageable);

    long countBySearchedAtBefore(Instant before);

    /** Each query and when it was searched, the oldest first: what a bulk refetch goes through. */
    @Query("SELECT s.queryNormalized, s.searchedAt FROM DtddSearchCache s ORDER BY s.searchedAt")
    List<Object[]> findAllAges();
}
