package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentFeedback;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentFeedbackRepository extends JpaRepository<ExperimentFeedback, UUID> {
    Optional<ExperimentFeedback> findByExperimentAndUser(Experiment experiment, UserAccount user);
    List<ExperimentFeedback> findByExperimentAndVariant(Experiment experiment, ExperimentVariant variant);
    Page<ExperimentFeedback> findByExperiment(Experiment experiment, Pageable pageable);

    @Query("SELECT AVG(f.npsScore) FROM ExperimentFeedback f WHERE f.experiment = :experiment AND f.variant = :variant")
    Double findAverageNpsByExperimentAndVariant(Experiment experiment, ExperimentVariant variant);
}
