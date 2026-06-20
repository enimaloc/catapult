package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ExperimentService {

    private final ExperimentRepository experimentRepository;
    private final ExperimentAssignmentRepository assignmentRepository;
    private final ExperimentEventRepository eventRepository;
    private final ExperimentOverrideRepository overrideRepository;
    private final ExperimentService self;

    private static final String CONTROL_KEY = "control";
    private final Set<String> knownKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public ExperimentService(ExperimentRepository experimentRepository, ExperimentAssignmentRepository assignmentRepository, ExperimentEventRepository eventRepository, ExperimentOverrideRepository overrideRepository, @Lazy ExperimentService self) {
        this.experimentRepository = experimentRepository;
        this.assignmentRepository = assignmentRepository;
        this.eventRepository = eventRepository;
        this.overrideRepository = overrideRepository;
        this.self = self;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Optional<Experiment> getExperiment(String name) {
        return experimentRepository.findByKey(name);
    }

    @Transactional
    public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
        Experiment experiment = experimentRepository.findByKey(experimentKey)
            .filter(e -> e.getStatus() == Experiment.Status.ACTIVE)
            .orElse(null);
        if (experiment == null) return Optional.empty();

        OverrideResult or = firstMatchingOverride(user, experiment);
        if (or.excluded()) return Optional.empty();
        if (or.forcedVariant().isPresent()) return or.forcedVariant().flatMap(fv -> applyForcedVariant(user, experiment, fv));

        Optional<ExperimentAssignment> existing = assignmentRepository.findByExperimentAndUser(experiment, user);
        if (existing.isPresent()) return Optional.of(existing.get().getVariant());
        if (!or.skipRules() && !isEligible(user, experiment, experimentKey)) return Optional.empty();

        return Optional.of(persistAssignment(weightedRandom(experiment.getVariants(), user, experimentKey), user, experiment));
    }

    @Transactional
    public void track(UserAccount user, String experimentKey, String eventKey) {
        Optional<Experiment> opt = experimentRepository.findByKey(experimentKey);
        if (opt.isEmpty()) return;
        Experiment experiment = opt.get();
        if (experiment.getStatus() != Experiment.Status.ACTIVE) return;

        assignmentRepository.findByExperimentAndUser(experiment, user).ifPresent(assignment -> {
            ExperimentEvent event = new ExperimentEvent();
            event.setExperiment(experiment);
            event.setVariant(assignment.getVariant());
            event.setUser(user);
            event.setEventKey(eventKey);
            eventRepository.save(event);
        });
    }

    @Transactional
    public void reassignAll(Experiment experiment) {
        List<UserAccount> users = assignmentRepository.findAllByExperiment(experiment)
            .stream().map(ExperimentAssignment::getUser).toList();
        assignmentRepository.deleteAllByExperiment(experiment);
        users.forEach(u -> self.getVariant(u, experiment.getKey()));
    }

    public List<ExperimentAssignment> getActiveAssignments(UserAccount user) {
        return assignmentRepository.findActiveByUser(user);
    }

    public Optional<ExperimentVariant> getAssignedVariant(UserAccount user, Experiment experiment) {
        return assignmentRepository.findByExperimentAndUser(experiment, user)
                .map(ExperimentAssignment::getVariant);
    }

    public boolean isRolledOut(UserAccount user, String experimentKey) {
        return assignmentRepository.findActiveByUser(user).stream()
            .filter(a -> a.getExperiment().getKey().equals(experimentKey))
            .findFirst()
            .map(a -> !a.getVariant().isControl())
            .orElse(false);
    }

    /**
     * Évalue le gate "rolled out" pour {@code experimentKey}, en déclenchant la
     * création de l'assignation depuis un éventuel override admin si l'experiment
     * est ACTIVE. Contrairement à {@link #isRolledOut}, lit pas seulement les
     * assignations existantes.
     * <p>
     * Encapsule l'accès au {@code variant.isControl} dans la même transaction
     * que {@code getVariant} pour éviter les {@code LazyInitializationException}
     * sur les proxys Hibernate côté caller.
     */
    @Transactional
    public boolean evaluateGate(UserAccount user, String experimentKey) {
        return self.getVariant(user, experimentKey)
            .map(v -> !v.isControl())
            .orElse(false);
    }

    public void markKnown(String key) {
        knownKeys.add(key);
    }

    public void ensureExists(String key, String... variantHints) {
        if (knownKeys.contains(key)) return;
        knownKeys.add(key);
        if (experimentRepository.findByKey(key).isPresent()) return;

        Experiment exp = new Experiment();
        exp.setKey(key);
        exp.setName(key);
        exp.setStatus(Experiment.Status.DRAFT);

        int i = 0;
        exp.getVariants().add(createVariant(exp, CONTROL_KEY, true, i++));
        for (String hint : variantHints) {
            if (!CONTROL_KEY.equals(hint)) exp.getVariants().add(createVariant(exp, hint, false, i++));
        }

        try {
            experimentRepository.save(exp);
            log.warn("[Experiments] Auto-created DRAFT '{}' from template", key);
        } catch (DataIntegrityViolationException ignored) {
            // race condition: another thread already created it
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private record OverrideResult(boolean excluded, boolean skipRules, Optional<ExperimentVariant> forcedVariant) {
        static OverrideResult exclude()                  { return new OverrideResult(true,  false, Optional.empty()); }
        static OverrideResult include()                  { return new OverrideResult(false, true,  Optional.empty()); }
        static OverrideResult force(ExperimentVariant v) { return new OverrideResult(false, true,  Optional.of(v));   }
        static OverrideResult cont()                     { return new OverrideResult(false, false, Optional.empty()); }
    }

    private Optional<ExperimentVariant> applyForcedVariant(UserAccount user, Experiment experiment, ExperimentVariant forcedVariant) {
        Optional<ExperimentAssignment> existing = assignmentRepository.findByExperimentAndUser(experiment, user);
        if (existing.isEmpty()) return Optional.of(persistAssignment(forcedVariant, user, experiment));
        ExperimentAssignment a = existing.get();
        if (!a.getVariant().getKey().equals(forcedVariant.getKey())) {
            String oldKey = a.getVariant().getKey();
            a.setVariant(forcedVariant);
            assignmentRepository.save(a);
            log.debug("Corrected assignment for user {} in '{}': {} → {}", user.getId(), experiment.getKey(), oldKey, forcedVariant.getKey());
        }
        return Optional.of(a.getVariant());
    }

    private OverrideResult firstMatchingOverride(UserAccount user, Experiment experiment) {
        return overrideRepository.findByExperimentOrderByPriorityAsc(experiment)
            .stream()
            .filter(o -> overrideMatches(user, o))
            .findFirst()
            .map(o -> switch (o.getAction()) {
                case FORCE_EXCLUDE -> OverrideResult.exclude();
                case FORCE_INCLUDE -> OverrideResult.include();
                case FORCE_VARIANT -> {
                    ExperimentVariant tv = o.getTargetVariant();
                    if (tv == null) {
                        log.warn("FORCE_VARIANT override {} has null targetVariant, skipping", o.getId());
                        yield OverrideResult.cont();
                    }
                    yield OverrideResult.force(tv);
                }
            })
            .orElse(OverrideResult.cont());
    }

    private boolean overrideMatches(UserAccount user, ExperimentOverride override) {
        return switch (override.getOverrideType()) {
            case USER      -> override.getTargetUser() != null
                              && override.getTargetUser().getId().equals(user.getId());
            case ATTRIBUTE -> evaluateAttribute(user,
                              override.getAttributeKey(),
                              override.getAttributeOp(),
                              override.getAttributeVal());
        };
    }

    private boolean isEligible(UserAccount user, Experiment experiment, String experimentKey) {
        if (!allRulesMatch(user, experiment)) return false;
        if (experiment.getRolloutPercentage() < 100) {
            return fnvBucket(user.getId().toString(), experimentKey) < experiment.getRolloutPercentage();
        }
        return true;
    }

    private boolean allRulesMatch(UserAccount user, Experiment experiment) {
        List<ExperimentAssignmentRule> rules = experiment.getRules();
        return !rules.isEmpty() && rules.stream().allMatch(rule -> ruleMatches(user, rule));
    }

    private boolean ruleMatches(UserAccount user, ExperimentAssignmentRule rule) {
        return switch (rule.getRuleType()) {
            case RANDOM    -> fnvBucket(user.getId().toString(), rule.getExperiment().getKey()) < rule.getPercentage();
            case ATTRIBUTE -> evaluateAttribute(user,
                              rule.getAttributeKey(),
                              rule.getAttributeOperator(),
                              rule.getAttributeValue());
            case MANUAL    -> false;
        };
    }

    private boolean evaluateAttribute(UserAccount user, String key, String op, String val) {
        double actual = switch (key) {
            case "account_age_days"          -> (System.currentTimeMillis() - user.getCreatedAt().toEpochMilli()) / 86_400_000.0;
            case "has_steam"                 -> user.getSteamId() != null ? 1.0 : 0.0;
            case "has_xbox", "has_battlenet" -> 0.0;
            default -> {
                log.warn("Unknown attribute key '{}', defaulting to no-match", key);
                yield Double.NaN;
            }
        };
        if (Double.isNaN(actual)) return false;
        double expected;
        try {
            expected = Double.parseDouble(val);
        } catch (NumberFormatException e) {
            log.warn("Invalid attribute value '{}', defaulting to no-match", val);
            return false;
        }
        return switch (op) {
            case "eq",  "==" -> actual == expected;
            case "neq", "!=" -> actual != expected;
            case "gt",  ">"  -> actual >  expected;
            case "gte", ">=" -> actual >= expected;
            case "lt",  "<"  -> actual <  expected;
            case "lte", "<=" -> actual <= expected;
            default          -> false;
        };
    }

    private ExperimentVariant createVariant(Experiment exp, String key, boolean control, int internalId) {
        ExperimentVariant v = new ExperimentVariant();
        v.setExperiment(exp);
        v.setKey(key);
        v.setName(key);
        v.setWeight(50);
        v.setControl(control);
        v.setInternalId(internalId);
        return v;
    }

    private ExperimentVariant persistAssignment(ExperimentVariant variant, UserAccount user, Experiment experiment) {
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(variant);
        assignment.setUser(user);
        assignmentRepository.save(assignment);
        log.debug("Assigned user {} to variant '{}' in experiment '{}'", user.getId(), variant.getKey(), experiment.getKey());
        return variant;
    }

    private ExperimentVariant weightedRandom(List<ExperimentVariant> variants, UserAccount user, String experimentKey) {
        int totalWeight = variants.stream().mapToInt(ExperimentVariant::getWeight).sum();
        if (totalWeight == 0) return variants.get(0);
        int bucket = fnvBucket(user.getId().toString() + ":variant", experimentKey) % totalWeight;
        int cumulative = 0;
        for (ExperimentVariant v : variants) {
            cumulative += v.getWeight();
            if (bucket < cumulative) return v;
        }
        return variants.get(variants.size() - 1);
    }

    private int fnvBucket(String part1, String part2) {
        byte[] bytes = (part1 + ":" + part2).getBytes(StandardCharsets.UTF_8);
        int h = 0x811c9dc5;
        for (byte b : bytes) {
            h ^= (b & 0xFF);
            h *= 0x01000193;
        }
        return (h & Integer.MAX_VALUE) % 100;
    }
}
