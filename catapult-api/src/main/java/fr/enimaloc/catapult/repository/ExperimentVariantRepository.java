package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ExperimentVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, UUID> {}
