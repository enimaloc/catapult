package fr.enimaloc.catapult.repository.tw;

import fr.enimaloc.catapult.domain.tw.TwDtddTopicMapping;
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

    /** The mappings of these DTDD topics (lower-cased names), each one with its TW and its least severe level. */
    @Query("SELECT m FROM TwDtddTopicMapping m WHERE LOWER(m.dtddTopicName) IN :lowered")
    List<TwDtddTopicMapping> findAllByDtddTopicNameInIgnoreCase(@Param("lowered") Set<String> lowered);

    void deleteAllByTwId(String twId);
}
