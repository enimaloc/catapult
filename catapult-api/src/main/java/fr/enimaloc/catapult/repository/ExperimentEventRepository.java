package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentEvent;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface ExperimentEventRepository extends JpaRepository<ExperimentEvent, UUID> {

    @Query("SELECT DISTINCT e.eventKey FROM ExperimentEvent e WHERE e.experiment = :experiment")
    List<String> findDistinctEventKeysByExperiment(Experiment experiment);

    long countByExperimentAndVariantAndEventKey(Experiment experiment, ExperimentVariant variant, String eventKey);

    void deleteByUser(UserAccount user);
}
