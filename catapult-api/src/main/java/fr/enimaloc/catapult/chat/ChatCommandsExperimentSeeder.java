package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.service.experiment.ExperimentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Makes sure the experiment gating the data-driven chat commands is known to the system at
 * startup. Creating it (DRAFT + control/enabled variants + default weights) is delegated to
 * {@link ExperimentService#ensureExists}; the admin then adjusts status, weights, rules and
 * overrides through {@code /admin/experiments}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCommandsExperimentSeeder {

    private final ExperimentService experimentService;

    @PostConstruct
    public void seed() {
        experimentService.ensureExists(fr.enimaloc.catapult.service.chatcommand.ChatCommandGate.EXPERIMENT_KEY, "control", "enabled");
        log.info("Chat commands experiment ensured: {}", fr.enimaloc.catapult.service.chatcommand.ChatCommandGate.EXPERIMENT_KEY);
    }
}
