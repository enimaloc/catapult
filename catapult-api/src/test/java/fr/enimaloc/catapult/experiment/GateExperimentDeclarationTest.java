package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.drops.DropsGate;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Every experiment key a gate checks must be declared by an {@link ExperimentSpec} class, or the gate stays closed. */
class GateExperimentDeclarationTest {

    @Test
    void everyGateExperimentKeyIsDeclaredByASpec() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ExperimentSpec.class));
        Set<String> declared = new HashSet<>();
        for (BeanDefinition def : scanner.findCandidateComponents("fr.enimaloc.catapult.experiment")) {
            Class<?> type = Class.forName(def.getBeanClassName());
            declared.add(type.getAnnotation(ExperimentSpec.class).key());
        }

        List<String> gateKeys = List.of(
                MinecraftGateService.EXPERIMENT_KEY,
                BattleNetGateService.EXPERIMENT_KEY,
                AnkamaGateService.EXPERIMENT_KEY,
                StreamTitleService.EXPERIMENT_KEY,
                RaidRestoreGateService.EXPERIMENT_KEY,
                DropsGate.EXPERIMENT_KEY);

        assertThat(declared).containsAll(gateKeys);
    }
}
