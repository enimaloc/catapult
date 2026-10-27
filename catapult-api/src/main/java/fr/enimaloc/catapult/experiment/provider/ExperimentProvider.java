package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;

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
