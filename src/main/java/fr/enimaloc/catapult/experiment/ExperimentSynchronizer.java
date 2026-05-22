package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignmentRule;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExperimentSynchronizer implements ApplicationRunner {

    private final ExperimentRepository experimentRepository;
    private final ExperimentService experimentService;
    private final ApplicationContext applicationContext;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        applicationContext.getBeansWithAnnotation(ExperimentSpec.class).values().forEach(bean -> {
            ExperimentSpec spec = AnnotationUtils.findAnnotation(bean.getClass(), ExperimentSpec.class);
            if (spec == null || experimentRepository.findByKey(spec.key()).isPresent()) {
                return;
            }
            experimentRepository.save(buildExperiment(spec));
            log.info("[Experiments] Registered '{}' ({})", spec.name(), spec.key());
        });

        experimentRepository.findAll().stream()
            .map(Experiment::getKey)
            .forEach(experimentService::markKnown);
    }

    private Experiment buildExperiment(ExperimentSpec spec) {
        Experiment exp = new Experiment();
        exp.setKey(spec.key());
        exp.setName(spec.name());
        exp.setDescription(spec.description().isEmpty() ? null : spec.description());
        exp.setStatus(Experiment.Status.DRAFT);

        for (ExperimentSpec.Variant vd : spec.variants()) {
            ExperimentVariant v = new ExperimentVariant();
            v.setExperiment(exp);
            v.setKey(vd.key());
            v.setName(vd.name());
            v.setWeight(vd.weight());
            v.setControl(vd.control());
            exp.getVariants().add(v);
        }

        for (ExperimentSpec.Rule rd : spec.rules()) {
            ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
            rule.setExperiment(exp);
            rule.setRuleType(rd.type());
            rule.setPriority(rd.priority());
            exp.getRules().add(rule);
        }

        return exp;
    }
}
