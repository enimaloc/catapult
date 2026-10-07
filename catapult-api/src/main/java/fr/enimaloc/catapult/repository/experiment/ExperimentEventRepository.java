package fr.enimaloc.catapult.repository.experiment;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentEvent;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
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
