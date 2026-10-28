package fr.enimaloc.catapult.service.minecraft;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central decision on whether the Minecraft feature is available to a
 * user: valid configuration AND experiment {@value #EXPERIMENT_KEY}.
 * Deliberately unconditional: it must answer "unavailable" even
 * when {@code minecraft.enabled=false} (the help depends on it).
 */
@Slf4j
@Service
public class MinecraftGateService {

    public static final String EXPERIMENT_KEY = "minecraft.integration";

    private final ExperimentService experimentService;
    private final UserAccountRepository userAccountRepository;
    private final boolean minecraftEnabled;
    private final String msaClientId;

    public MinecraftGateService(ExperimentService experimentService,
                                UserAccountRepository userAccountRepository,
                                @Value("${minecraft.enabled:false}") boolean minecraftEnabled,
                                @Value("${minecraft.msa-client-id:}") String msaClientId) {
        this.experimentService = experimentService;
        this.userAccountRepository = userAccountRepository;
        this.minecraftEnabled = minecraftEnabled;
        this.msaClientId = msaClientId == null ? "" : msaClientId.trim();
    }

    @PostConstruct
    void warnIfMisconfigured() {
        if (minecraftEnabled && (msaClientId == null || msaClientId.isBlank())) {
            log.warn("minecraft.enabled=true but minecraft.msa-client-id is empty: the Minecraft feature will stay hidden");
        }
    }

    public boolean isConfigured() {
        return minecraftEnabled && msaClientId != null && !msaClientId.isBlank();
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
