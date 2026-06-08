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

@Slf4j
@Component
@RequiredArgsConstructor
public class ExperimentSynchronizer implements ApplicationRunner {

    private final Map<String, List<ExperimentOverride>> waitingVariant = new HashMap<>();
    private final ExperimentRepository experimentRepository;
    private final ExperimentService experimentService;
    private final ApplicationContext applicationContext;
    private final UserAccountRepository userAccountRepository;
    private final ExperimentOverrideRepository experimentOverrideRepository;

    @Override
    @Transactional
    @SuppressWarnings("NullableProblems")
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
        List<ExperimentOverride> waiting = waitingVariant.get(user.getTwitchUsername());
        if (waiting == null) return;
        log.info("Updating registered variant for {}", user.getTwitchUsername());
        for (ExperimentOverride override : waiting) {
            override.setTargetUser(user);
            experimentOverrideRepository.save(override);
            log.debug("Saved variant {}:{} for user {}", override.getExperiment().getName(), override.getTargetVariant(), user.getTwitchUsername());
        }
    }

    private void createExperiment(ExperimentSpec spec) {
        Experiment exp = new Experiment();
        exp.setKey(spec.key());
        exp.setName(spec.name());
        exp.setDescription(spec.description().isEmpty() ? null : spec.description());
        exp.setStatus(Experiment.Status.DRAFT);

        int i = 0;
        createVariantsForExperiment(spec, exp, i);
        createRules(spec, exp);
        experimentRepository.save(exp);

        createOverrides(spec, exp);
    }

    private void createOverrides(ExperimentSpec spec, Experiment exp) {
        for (ExperimentSpec.Override od : spec.overrides()) {
            createOverride(od, exp);
        }
    }

    private void createOverride(ExperimentSpec.Override od, Experiment exp) {
        ExperimentOverride override = new ExperimentOverride();
        override.setExperiment(exp);
        override.setOverrideType(od.type());
        override.setAction(od.action());
        override.setPriority(od.priority());

        if (od.action() == ExperimentOverride.OverrideAction.FORCE_VARIANT) {
            if (od.targetVariantId().isBlank()) {
                throw new IllegalStateException("Variant not specified");
            }
            ExperimentVariant tv = exp.getVariants().stream()
                    .filter(variant -> variant.getKey().equals(od.targetVariantId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Variant %s does not exist".formatted(od.targetVariantId())));
            override.setTargetVariant(tv);
        }
        boolean canBeCompleted = true;
        if (od.type() == ExperimentOverride.OverrideType.USER) {
            if (od.twitchUsername().isBlank()) {
                throw new IllegalStateException("Account not specified");
            }
            UserAccount u = userAccountRepository.findByTwitchUsername(od.twitchUsername().trim()).orElse(null);
            if (u == null) {
                canBeCompleted = false;
                waitingVariant.computeIfAbsent(od.twitchUsername(), unused -> new ArrayList<>()).add(override);
            }
            override.setTargetUser(u);
        }/* else if (overrideType == ExperimentOverride.OverrideType.ATTRIBUTE) {
            override.setAttributeKey(attributeKey);
            override.setAttributeOp(attributeOp);
            override.setAttributeVal(attributeVal);
        }*/ // TODO Implement
        if (canBeCompleted) experimentOverrideRepository.save(override);
    }

    private static void createRules(ExperimentSpec spec, Experiment exp) {
        for (ExperimentSpec.Rule rd : spec.rules()) exp.getRules().add(createRule(exp, rd));
    }

    private static void createVariantsForExperiment(ExperimentSpec spec, Experiment exp, int i) {
        for (ExperimentSpec.Variant vd : spec.variants()) exp.getVariants().add(createVariant(exp, vd, i++));
    }

    private static ExperimentVariant createVariant(Experiment exp, ExperimentSpec.Variant vd, int internalId) {
        ExperimentVariant v = new ExperimentVariant();
        v.setExperiment(exp);
        v.setKey(vd.key());
        v.setName(vd.name());
        v.setWeight(vd.weight());
        v.setControl(vd.control());
        v.setInternalId(internalId);
        return v;
    }

    private static ExperimentAssignmentRule createRule(Experiment exp, ExperimentSpec.Rule rd) {
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setExperiment(exp);
        rule.setRuleType(rd.type());
        rule.setPriority(rd.priority());
        if (rd.type() == ExperimentAssignmentRule.RuleType.RANDOM) rule.setPercentage(rd.percent());
        return rule;
    }
}
