package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Garantit que l'experiment qui gate les commandes chat data-driven existe au
 * démarrage avec la bonne distribution (100% control / 0% enabled, dark launch)
 * en statut DRAFT — l'admin l'active manuellement quand prêt.
 * <p>
 * Réconciliation idempotente : si l'experiment existe déjà mais avec des poids
 * différents (par exemple créé par {@code ensureExists} qui met 50/50 par
 * défaut), on corrige les poids sans toucher au statut courant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCommandsExperimentSeeder {

    private static final String CONTROL_KEY = "control";
    private static final String ENABLED_KEY = "enabled";

    private final ExperimentRepository experimentRepository;
    private final ExperimentService experimentService;

    @PostConstruct
    @Transactional
    public void seed() {
        boolean creating = experimentRepository.findByKey(ChatCommandListener.EXPERIMENT_KEY).isEmpty();
        Experiment experiment = experimentRepository.findByKey(ChatCommandListener.EXPERIMENT_KEY)
            .orElseGet(this::createExperiment);

        boolean dirty = creating;

        ExperimentVariant control = findOrAddVariant(experiment, CONTROL_KEY, true, 0);
        ExperimentVariant enabled = findOrAddVariant(experiment, ENABLED_KEY, false, 1);

        if (control.getWeight() != 100) { control.setWeight(100); dirty = true; }
        if (enabled.getWeight() != 0)   { enabled.setWeight(0);   dirty = true; }

        if (dirty) {
            experimentRepository.save(experiment);
            log.info("[chat.commands] Experiment {} — status={}, control=100%, enabled=0%",
                creating ? "created" : "reconciled", experiment.getStatus());
        }

        // Empêche ExperimentService.ensureExists de réinitialiser l'experiment.
        experimentService.markKnown(ChatCommandListener.EXPERIMENT_KEY);
    }

    private Experiment createExperiment() {
        Experiment exp = new Experiment();
        exp.setKey(ChatCommandListener.EXPERIMENT_KEY);
        exp.setName(ChatCommandListener.EXPERIMENT_KEY);
        exp.setStatus(Experiment.Status.DRAFT);
        exp.setRolloutPercentage(100);
        return exp;
    }

    private ExperimentVariant findOrAddVariant(Experiment exp, String key, boolean control, int internalId) {
        Optional<ExperimentVariant> existing = exp.getVariants().stream()
            .filter(v -> key.equals(v.getKey()))
            .findFirst();
        if (existing.isPresent()) return existing.get();

        ExperimentVariant v = new ExperimentVariant();
        v.setExperiment(exp);
        v.setKey(key);
        v.setName(key);
        v.setControl(control);
        v.setInternalId(internalId);
        v.setWeight(0); // overridden right after by the caller
        exp.getVariants().add(v);
        return v;
    }
}
