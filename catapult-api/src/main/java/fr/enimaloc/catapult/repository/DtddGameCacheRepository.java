package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.DtddGameCache;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DtddGameCacheRepository extends JpaRepository<DtddGameCache, Long> {

    @Query("SELECT g FROM DtddGameCache g WHERE LOWER(g.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<DtddGameCache> searchByNameLike(@Param("q") String q, Pageable pageable);
}
