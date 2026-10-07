package fr.enimaloc.catapult.repository.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, UUID> {}
