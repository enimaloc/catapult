package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Décision centralisée de disponibilité de la feature Minecraft pour un
 * utilisateur : configuration valide ET experiment {@value #EXPERIMENT_KEY}.
 * Volontairement non conditionnel : il doit répondre « indisponible » même
 * quand {@code minecraft.enabled=false} (l'aide en dépend).
 */
@Slf4j
@Service
public class MinecraftGateService {

    public static final String EXPERIMENT_KEY = "minecraft.integration";

    private final ExperimentService experimentService;
    private final boolean minecraftEnabled;
    private final String msaClientId;

    public MinecraftGateService(ExperimentService experimentService,
                                @Value("${minecraft.enabled:false}") boolean minecraftEnabled,
                                @Value("${minecraft.msa-client-id:}") String msaClientId) {
        this.experimentService = experimentService;
        this.minecraftEnabled = minecraftEnabled;
        this.msaClientId = msaClientId == null ? "" : msaClientId.trim();
    }

    @PostConstruct
    void warnIfMisconfigured() {
        if (minecraftEnabled && (msaClientId == null || msaClientId.isBlank())) {
            log.warn("minecraft.enabled=true mais minecraft.msa-client-id est vide : la feature Minecraft restera masquée");
        }
    }

    public boolean isConfigured() {
        return minecraftEnabled && msaClientId != null && !msaClientId.isBlank();
    }

    /** Chemins user-facing : déclenche l'assignation à l'experiment (visite de page). */
    public boolean isAvailableFor(UserAccount user) {
        return isConfigured() && experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }

    /** Chemins background (scheduler/getter) : lecture seule, aucune assignation. */
    public boolean isAvailableReadOnly(UserAccount user) {
        return isConfigured() && experimentService.isRolledOut(user, EXPERIMENT_KEY);
    }
}
