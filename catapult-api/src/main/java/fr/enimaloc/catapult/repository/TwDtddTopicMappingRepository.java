package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwDtddTopicMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface TwDtddTopicMappingRepository extends JpaRepository<TwDtddTopicMapping, Long> {

    List<TwDtddTopicMapping> findAllByTwId(String twId);

    @Query("SELECT DISTINCT m.twId FROM TwDtddTopicMapping m " +
           "WHERE LOWER(m.dtddTopicName) IN :lowered")
    Set<String> findTwIdsByDtddTopicNameInIgnoreCase(@Param("lowered") Set<String> lowered);

    void deleteAllByTwId(String twId);
}
