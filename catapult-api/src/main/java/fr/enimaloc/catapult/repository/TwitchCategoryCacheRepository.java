package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchCategoryCache;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface TwitchCategoryCacheRepository extends JpaRepository<TwitchCategoryCache, String> {

    List<TwitchCategoryCache> findByNameContainingIgnoreCaseAndCachedAtAfter(
            String query, Instant since, Pageable pageable);

    @Query(value = "SELECT COALESCE(MAX(CAST(id AS BIGINT)), 0) FROM twitch_category_cache WHERE id ~ '^[0-9]+$'",
           nativeQuery = true)
    long findMaxNumericId();
}
