package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExperimentOverrideRepository extends JpaRepository<ExperimentOverride, UUID> {

    @Query("SELECT o FROM ExperimentOverride o LEFT JOIN FETCH o.targetUser LEFT JOIN FETCH o.targetVariant WHERE o.experiment = :exp ORDER BY o.priority ASC")
    List<ExperimentOverride> findByExperimentOrderByPriorityAsc(@Param("exp") Experiment exp);
}
