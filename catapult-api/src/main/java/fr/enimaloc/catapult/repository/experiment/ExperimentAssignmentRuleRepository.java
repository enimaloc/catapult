package fr.enimaloc.catapult.repository.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExperimentAssignmentRuleRepository extends JpaRepository<ExperimentAssignmentRule, UUID> {}
