package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbGameCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IgdbGameCacheRepository extends JpaRepository<IgdbGameCacheEntry, String> {

    List<IgdbGameCacheEntry> findByCachedAtAfter(Instant since);
}
