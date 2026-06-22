package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwIgdbDescriptorMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface TwIgdbDescriptorMappingRepository extends JpaRepository<TwIgdbDescriptorMapping, Long> {

    List<TwIgdbDescriptorMapping> findAllByTwId(String twId);

    @Query("SELECT DISTINCT m.twId FROM TwIgdbDescriptorMapping m WHERE m.descriptorId IN :ids")
    Set<String> findTwIdsByDescriptorIds(@Param("ids") Set<Long> descriptorIds);

    void deleteAllByTwId(String twId);
}
