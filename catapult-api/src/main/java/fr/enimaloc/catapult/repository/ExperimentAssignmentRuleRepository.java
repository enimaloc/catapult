package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExperimentAssignmentRuleRepository extends JpaRepository<ExperimentAssignmentRule, UUID> {}
