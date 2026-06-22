package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwSteamContentIdMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface TwSteamContentIdMappingRepository extends JpaRepository<TwSteamContentIdMapping, Long> {

    List<TwSteamContentIdMapping> findAllByTwId(String twId);

    @Query("SELECT DISTINCT m.twId FROM TwSteamContentIdMapping m WHERE m.steamContentId IN :ids")
    Set<String> findTwIdsBySteamContentIdIn(@Param("ids") Set<Integer> contentIds);

    void deleteAllByTwId(String twId);
}
