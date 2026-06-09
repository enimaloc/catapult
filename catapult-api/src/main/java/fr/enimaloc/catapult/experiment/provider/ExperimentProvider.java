package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;

import java.util.List;
import java.util.Optional;

public interface ExperimentProvider {

    String type();

    Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey);

    List<ExperimentSummary> listExperiments();

    Optional<String> adminUrl();

    void trackEvent(UserAccount user, String experimentKey, String eventKey);

    void importExperiments(List<Experiment> experiments);

    boolean isHealthy();
}
