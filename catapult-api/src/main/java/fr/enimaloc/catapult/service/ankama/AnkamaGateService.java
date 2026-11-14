package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central decision on whether the Ankama feature is available to a user: {@code ankama.enabled} AND experiment
 * {@value #EXPERIMENT_KEY}. Deliberately unconditional: it must answer "unavailable" even when
 * {@code ankama.enabled=false}.
 */
@Service
public class AnkamaGateService {

    public static final String EXPERIMENT_KEY = "ankama.integration";

    private final ExperimentService experimentService;
    private final UserAccountRepository userAccountRepository;
    private final boolean ankamaEnabled;

    public AnkamaGateService(ExperimentService experimentService,
                             UserAccountRepository userAccountRepository,
                             @Value("${ankama.enabled:false}") boolean ankamaEnabled) {
        this.experimentService = experimentService;
        this.userAccountRepository = userAccountRepository;
        this.ankamaEnabled = ankamaEnabled;
    }

    public boolean isConfigured() {
        return ankamaEnabled;
    }

    /** User-facing paths: triggers the assignment to the experiment (page visit). */
    public boolean isAvailableFor(UserAccount user) {
        return isConfigured() && experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }

    /** Like {@link #isAvailableFor(UserAccount)} for the account {@code userId}; an unknown account gives false. */
    public boolean isAvailableForAccount(UUID userId) {
        return userAccountRepository.findById(userId).map(this::isAvailableFor).orElse(false);
    }

    /** Background paths (scheduler/getter): read-only, no assignment. */
    public boolean isAvailableReadOnly(UserAccount user) {
        return isConfigured() && experimentService.isRolledOut(user, EXPERIMENT_KEY);
    }
}
