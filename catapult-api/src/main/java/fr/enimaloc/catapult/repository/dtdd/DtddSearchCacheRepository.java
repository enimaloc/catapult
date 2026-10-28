package fr.enimaloc.catapult.repository.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddSearchCache;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DtddSearchCacheRepository extends JpaRepository<DtddSearchCache, String> {
}
