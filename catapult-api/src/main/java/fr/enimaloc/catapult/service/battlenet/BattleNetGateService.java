package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central decision on whether the Battle.net feature is available to a
 * user: valid configuration AND experiment {@value #EXPERIMENT_KEY}.
 * Deliberately unconditional: it must answer "unavailable" even
 * when {@code battlenet.enabled=false} (the help depends on it).
 */
@Slf4j
@Service
public class BattleNetGateService {

    public static final String EXPERIMENT_KEY = "battlenet.integration";

    private final ExperimentService experimentService;
    private final UserAccountRepository userAccountRepository;
    private final boolean battleNetEnabled;
    private final String clientId;
    private final String clientSecret;

    public BattleNetGateService(ExperimentService experimentService,
                                UserAccountRepository userAccountRepository,
                                @Value("${battlenet.enabled:false}") boolean battleNetEnabled,
                                @Value("${battlenet.client-id:}") String clientId,
                                @Value("${battlenet.client-secret:}") String clientSecret) {
        this.experimentService = experimentService;
        this.userAccountRepository = userAccountRepository;
        this.battleNetEnabled = battleNetEnabled;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    @PostConstruct
    void warnIfMisconfigured() {
        if (battleNetEnabled && (clientId.isBlank() || clientSecret.isBlank())) {
            log.warn("battlenet.enabled=true but battlenet.client-id or battlenet.client-secret is empty: "
                    + "the Battle.net feature will stay hidden");
        }
    }

    public boolean isConfigured() {
        return battleNetEnabled && !clientId.isBlank() && !clientSecret.isBlank();
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
