package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.Experiment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentRepository extends JpaRepository<Experiment, UUID> {
    Optional<Experiment> findByKey(String key);
}
