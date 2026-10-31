package fr.enimaloc.catapult.repository.igdb;

import fr.enimaloc.catapult.domain.igdb.IgdbGameCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IgdbGameCacheRepository extends JpaRepository<IgdbGameCacheEntry, String> {

    List<IgdbGameCacheEntry> findByCachedAtAfter(Instant since);
}
