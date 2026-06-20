package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.service.ExperimentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Garantit que l'experiment qui gate les commandes chat data-driven est connu
 * du système au démarrage. La création (DRAFT + variants control/enabled +
 * poids par défaut) est déléguée à {@link ExperimentService#ensureExists} —
 * c'est l'admin qui ajuste statut, poids, règles et overrides via
 * {@code /admin/experiments}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCommandsExperimentSeeder {

    private final ExperimentService experimentService;

    @PostConstruct
    public void seed() {
        experimentService.ensureExists(ChatCommandListener.EXPERIMENT_KEY, "control", "enabled");
        log.info("Chat commands experiment ensured: {}", ChatCommandListener.EXPERIMENT_KEY);
    }
}
