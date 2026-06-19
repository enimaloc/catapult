package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.service.ExperimentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Garantit que l'experiment qui gate les commandes chat data-driven existe au démarrage.
 * Distribution par défaut : 100% control / 0% enabled (dark launch).
 * Activation per-user via override admin sur /admin/experiments.
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
