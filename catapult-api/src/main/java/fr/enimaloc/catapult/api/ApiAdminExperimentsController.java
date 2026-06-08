package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.ExperimentAssignmentRule;
import fr.enimaloc.catapult.domain.ExperimentFeedback;
import fr.enimaloc.catapult.domain.ExperimentOverride;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ExperimentActivatedEvent;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRuleRepository;
import fr.enimaloc.catapult.repository.ExperimentEventRepository;
import fr.enimaloc.catapult.repository.ExperimentFeedbackRepository;
import fr.enimaloc.catapult.repository.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.ExperimentVariantRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/experiments")
@RequiredArgsConstructor
public class ApiAdminExperimentsController {

    private final ExperimentRepository experimentRepository;
    private final ExperimentEventRepository eventRepository;
    private final ExperimentFeedbackRepository feedbackRepository;
    private final ExperimentAssignmentRepository assignmentRepository;
    private final UserAccountRepository userAccountRepository;
    private final StatisticsService statisticsService;
    private final ExperimentOverrideRepository overrideRepository;
    private final ExperimentAssignmentRuleRepository ruleRepository;
    private final ExperimentVariantRepository variantRepository;
    private final ExperimentService experimentService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping
    public List<Experiment> list() {
        return experimentRepository.findAll();
    }

    @Transactional(readOnly = true)
    @GetMapping("/{id}")
    public ExperimentDetailData detail(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        List<String> eventKeys = eventRepository.findDistinctEventKeysByExperiment(exp);

        List<ConversionStat> conversionStats = new ArrayList<>();
        if (exp.getVariants().size() >= 2) {
            ExperimentVariant control = exp.getVariants().stream()
                    .filter(ExperimentVariant::isControl).findFirst()
                    .orElse(exp.getVariants().get(0));
            long controlN = assignmentRepository.countByExperimentAndVariant(exp, control);

            for (String eventKey : eventKeys) {
                long controlConversions = eventRepository.countByExperimentAndVariantAndEventKey(exp, control, eventKey);
                for (ExperimentVariant variant : exp.getVariants()) {
                    if (variant.equals(control)) continue;
                    long variantN = assignmentRepository.countByExperimentAndVariant(exp, variant);
                    long variantConversions = eventRepository.countByExperimentAndVariantAndEventKey(exp, variant, eventKey);
                    StatisticsService.ZTestResult sig = statisticsService.twoProportionZTest(
                            controlConversions, controlN, variantConversions, variantN);
                    conversionStats.add(new ConversionStat(eventKey, variant.getKey(), variantConversions, variantN, sig));
                }
            }
        }

        List<NpsStat> npsStats = exp.getVariants().stream().map(v -> {
            List<ExperimentFeedback> feedbacks = feedbackRepository.findByExperimentAndVariant(exp, v);
            int[] scores = feedbacks.stream().mapToInt(ExperimentFeedback::getNpsScore).toArray();
            Double avg = feedbackRepository.findAverageNpsByExperimentAndVariant(exp, v);
            return new NpsStat(v.getKey(), statisticsService.npsScore(scores), avg);
        }).toList();

        boolean hasManualRule = exp.getRules().stream()
                .anyMatch(r -> r.getRuleType() == ExperimentAssignmentRule.RuleType.MANUAL);

        return new ExperimentDetailData(
                exp, eventKeys, conversionStats, npsStats,
                feedbackRepository.findByExperiment(exp, PageRequest.of(0, 20)).getContent(),
                hasManualRule,
                assignmentRepository.findByExperiment(exp, PageRequest.of(0, 20)).getContent(),
                overrideRepository.findByExperimentOrderByPriorityAsc(exp)
        );
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() != Experiment.Status.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment not in DRAFT status");
        }
        exp.setStatus(Experiment.Status.ACTIVE);
        exp.setStartedAt(Instant.now());
        experimentRepository.save(exp);
        eventPublisher.publishEvent(new ExperimentActivatedEvent(this, exp.getId(), exp.getKey()));
    }

    @PostMapping("/{id}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() != Experiment.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment not ACTIVE");
        }
        exp.setStatus(Experiment.Status.PAUSED);
        experimentRepository.save(exp);
    }

    @PostMapping("/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() == Experiment.Status.DRAFT || exp.getStatus() == Experiment.Status.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot end experiment in current status");
        }
        exp.setStatus(Experiment.Status.ENDED);
        exp.setEndedAt(Instant.now());
        experimentRepository.save(exp);
    }

    @PostMapping("/{id}/assign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignUser(@PathVariable UUID id, @RequestBody AssignRequest body) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() != Experiment.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment not ACTIVE");
        }
        UserAccount user = userAccountRepository.findByTwitchUsername(body.twitchUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (assignmentRepository.findByExperimentAndUser(exp, user).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already assigned");
        }
        if (!exp.getVariants().isEmpty()) {
            ExperimentVariant controlVariant = exp.getVariants().stream()
                    .filter(ExperimentVariant::isControl).findFirst()
                    .orElse(exp.getVariants().get(0));
            ExperimentAssignment assignment = new ExperimentAssignment();
            assignment.setExperiment(exp);
            assignment.setVariant(controlVariant);
            assignment.setUser(user);
            assignmentRepository.save(assignment);
        }
    }

    @PostMapping("/{id}/variants/weights")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateWeights(@PathVariable UUID id, @RequestBody Map<UUID, Integer> weights) {
        Experiment exp = findOrThrow(id);
        for (ExperimentVariant v : exp.getVariants()) {
            Integer weight = weights.get(v.getId());
            if (weight != null) {
                v.setWeight(Math.max(0, weight));
                variantRepository.save(v);
            }
        }
    }

    @PostMapping("/{id}/rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addRule(@PathVariable UUID id, @RequestBody AddRuleRequest body) {
        Experiment exp = findOrThrow(id);
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setExperiment(exp);
        rule.setRuleType(body.ruleType());
        rule.setPriority(body.priority());
        if (body.ruleType() == ExperimentAssignmentRule.RuleType.RANDOM) {
            rule.setPercentage(body.percentage());
        } else if (body.ruleType() == ExperimentAssignmentRule.RuleType.ATTRIBUTE) {
            rule.setAttributeKey(body.attributeKey());
            rule.setAttributeOperator(body.attributeOperator());
            rule.setAttributeValue(body.attributeValue());
        }
        ruleRepository.save(rule);
    }

    @PostMapping("/{id}/rules/{ruleId}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id, @PathVariable UUID ruleId) {
        findOrThrow(id);
        ExperimentAssignmentRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (rule.getExperiment() == null || !rule.getExperiment().getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ruleRepository.delete(rule);
    }

    @PostMapping("/{id}/overrides")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addOverride(@PathVariable UUID id, @RequestBody AddOverrideRequest body) {
        Experiment exp = findOrThrow(id);
        ExperimentOverride override = new ExperimentOverride();
        override.setExperiment(exp);
        override.setOverrideType(body.overrideType());
        override.setAction(body.action());
        override.setPriority(body.priority());

        if (body.overrideType() == ExperimentOverride.OverrideType.USER) {
            if (body.twitchUsername() == null || body.twitchUsername().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing twitchUsername");
            }
            UserAccount u = userAccountRepository.findByTwitchUsername(body.twitchUsername().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
            override.setTargetUser(u);
        } else if (body.overrideType() == ExperimentOverride.OverrideType.ATTRIBUTE) {
            override.setAttributeKey(body.attributeKey());
            override.setAttributeOp(body.attributeOp());
            override.setAttributeVal(body.attributeVal());
        }

        if (body.action() == ExperimentOverride.OverrideAction.FORCE_VARIANT) {
            if (body.targetVariantId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing targetVariantId");
            }
            Optional<ExperimentVariant> tv = variantRepository.findById(body.targetVariantId());
            if (tv.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found");
            }
            override.setTargetVariant(tv.get());
        }
        overrideRepository.save(override);
    }

    @PostMapping("/{id}/overrides/{overrideId}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOverride(@PathVariable UUID id, @PathVariable UUID overrideId) {
        findOrThrow(id);
        ExperimentOverride override = overrideRepository.findById(overrideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (override.getExperiment() == null || !override.getExperiment().getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        overrideRepository.delete(override);
    }

    @PostMapping("/{id}/reassign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reassign(@PathVariable UUID id) {
        experimentService.reassignAll(findOrThrow(id));
    }

    private Experiment findOrThrow(UUID id) {
        return experimentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record ExperimentDetailData(
            Experiment experiment,
            List<String> eventKeys,
            List<ConversionStat> conversionStats,
            List<NpsStat> npsStats,
            List<ExperimentFeedback> feedbackPage,
            boolean hasManualRule,
            List<ExperimentAssignment> participantPage,
            List<ExperimentOverride> overrides
    ) {}

    public record ConversionStat(String eventKey, String variantKey, long conversions, long participants, StatisticsService.ZTestResult significance) {}

    public record NpsStat(String variantKey, int npsScore, Double averageRaw) {}

    public record AssignRequest(String twitchUsername) {}

    public record AddRuleRequest(
            ExperimentAssignmentRule.RuleType ruleType,
            int priority,
            Integer percentage,
            String attributeKey,
            String attributeOperator,
            String attributeValue
    ) {}

    public record AddOverrideRequest(
            ExperimentOverride.OverrideType overrideType,
            ExperimentOverride.OverrideAction action,
            int priority,
            String twitchUsername,
            String attributeKey,
            String attributeOp,
            String attributeVal,
            UUID targetVariantId
    ) {}
}
