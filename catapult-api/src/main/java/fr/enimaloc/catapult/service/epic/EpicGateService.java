package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central decision on whether the Epic feature is available to a
 * user: valid configuration AND experiment {@value #EXPERIMENT_KEY}.
 * Deliberately unconditional: it must answer "unavailable" even
 * when {@code epic.enabled=false} (the help depends on it).
 */
@Slf4j
@Service
public class EpicGateService {

    public static final String EXPERIMENT_KEY = "epic.integration";

    private final ExperimentService experimentService;
    private final UserAccountRepository userAccountRepository;
    private final boolean epicEnabled;
    private final String clientId;
    private final String clientSecret;

    public EpicGateService(ExperimentService experimentService,
                           UserAccountRepository userAccountRepository,
                           @Value("${epic.enabled:false}") boolean epicEnabled,
                           @Value("${epic.client-id:}") String clientId,
                           @Value("${epic.client-secret:}") String clientSecret) {
        this.experimentService = experimentService;
        this.userAccountRepository = userAccountRepository;
        this.epicEnabled = epicEnabled;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    @PostConstruct
    void warnIfMisconfigured() {
        if (epicEnabled && (clientId.isBlank() || clientSecret.isBlank())) {
            log.warn("epic.enabled=true but epic.client-id or epic.client-secret is empty: "
                    + "the Epic feature will stay hidden");
        }
    }

    public boolean isConfigured() {
        return epicEnabled && !clientId.isBlank() && !clientSecret.isBlank();
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
