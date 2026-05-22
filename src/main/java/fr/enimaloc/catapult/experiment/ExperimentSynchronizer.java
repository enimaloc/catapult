package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExperimentSynchronizer implements ApplicationRunner {
    public static final Map<String, List<ExperimentOverride>> WAITING_VARIANT = new HashMap();

    private final ExperimentRepository experimentRepository;
    private final ExperimentService experimentService;
    private final ApplicationContext applicationContext;
    private final UserAccountRepository userAccountRepository;
    private final ExperimentOverrideRepository experimentOverrideRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        applicationContext.getBeansWithAnnotation(ExperimentSpec.class).values().forEach(bean -> {
            ExperimentSpec spec = AnnotationUtils.findAnnotation(bean.getClass(), ExperimentSpec.class);
            if (spec == null || experimentRepository.findByKey(spec.key()).isPresent()) {
                return;
            }
            createExperiment(spec);
            log.info("[Experiments] Registered '{}' ({})", spec.name(), spec.key());
        });

        experimentRepository.findAll().stream()
                .map(Experiment::getKey)
                .forEach(experimentService::markKnown);
    }

    public void updateVariant(UserAccount user) {
        if (!WAITING_VARIANT.containsKey(user.getTwitchUsername())) return;
        log.info("Updating registered variant for {}", user.getTwitchUsername());
        for (ExperimentOverride override : WAITING_VARIANT.get(user.getTwitchUsername())) {
            override.setTargetUser(user);
            experimentOverrideRepository.save(override);
            log.debug("Saved variant {}:{} for user {}", override.getExperiment().getName(), override.getTargetVariant(), user.getTwitchUsername());
        }
    }

    private Experiment createExperiment(ExperimentSpec spec) {
        Experiment exp = new Experiment();
        exp.setKey(spec.key());
        exp.setName(spec.name());
        exp.setDescription(spec.description().isEmpty() ? null : spec.description());
        exp.setStatus(Experiment.Status.DRAFT);

        int i = 0;
        for (ExperimentSpec.Variant vd : spec.variants()) {
            ExperimentVariant v = new ExperimentVariant();
            v.setExperiment(exp);
            v.setKey(vd.key());
            v.setName(vd.name());
            v.setWeight(vd.weight());
            v.setControl(vd.control());
            v.setInternalId(i++);
            exp.getVariants().add(v);
        }

        for (ExperimentSpec.Rule rd : spec.rules()) {
            ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
            rule.setExperiment(exp);
            rule.setRuleType(rd.type());
            rule.setPriority(rd.priority());
            if (rd.type() == ExperimentAssignmentRule.RuleType.RANDOM) {
                rule.setPercentage(rd.percent());
            }
            exp.getRules().add(rule);
        }
        experimentRepository.save(exp);

        for (ExperimentSpec.Override od : spec.overrides()) {
            ExperimentOverride override = new ExperimentOverride();
            override.setExperiment(exp);
            override.setOverrideType(od.type());
            override.setAction(od.action());
            override.setPriority(od.priority());

            if (od.action() == ExperimentOverride.OverrideAction.FORCE_VARIANT) {
                if (od.targetVariantId().isBlank()) {
                    throw new IllegalStateException("Variant not specified");
                }
                Optional<ExperimentVariant> tv = exp.getVariants().stream()
                        .filter(variant -> variant.getKey().equals(od.targetVariantId()))
                        .findFirst();
                if (tv.isEmpty()) {
                    throw new IllegalStateException("Variant %s does not exist".formatted(od.targetVariantId()));
                }
                override.setTargetVariant(tv.get());
            }
            boolean canBeCompleted = true;
            if (od.type() == ExperimentOverride.OverrideType.USER) {
                if (od.twitchUsername().isBlank()) {
                    throw new IllegalStateException("Account not specified");
                }
                UserAccount u = userAccountRepository.findByTwitchUsername(od.twitchUsername().trim()).orElse(null);
                if (u == null) {
                    canBeCompleted = false;
                    WAITING_VARIANT.computeIfAbsent(od.twitchUsername(), unused -> new ArrayList<>()).add(override);
                }
                override.setTargetUser(u);
            }/* else if (overrideType == ExperimentOverride.OverrideType.ATTRIBUTE) {
                override.setAttributeKey(attributeKey);
                override.setAttributeOp(attributeOp);
                override.setAttributeVal(attributeVal);
            }*/ // TODO Implement
            if (canBeCompleted) experimentOverrideRepository.save(override);
        }

        return exp;
    }
}
