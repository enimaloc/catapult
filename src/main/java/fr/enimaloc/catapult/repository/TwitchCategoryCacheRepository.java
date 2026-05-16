package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchCategoryCache;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TwitchCategoryCacheRepository extends JpaRepository<TwitchCategoryCache, String> {

    List<TwitchCategoryCache> findByNameContainingIgnoreCaseAndCachedAtAfter(
            String query, Instant since, Pageable pageable);
}
