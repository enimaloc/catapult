package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.event.ExperimentActivatedEvent;
import fr.enimaloc.catapult.repository.*;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Controller
@RequestMapping("/admin/experiments")
@RequiredArgsConstructor
public class AdminExperimentsController {

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

    public static final String REDIRECT_EXPERIMENTS_LIST = "redirect:/admin/experiments";
    public static final String REDIRECT_EXPERIMENT = "redirect:/admin/experiments/";

    @GetMapping
    public String list(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        model.addAttribute("experiments", experimentRepository.findAll());
        return "admin/experiments";
    }

    @Transactional(readOnly = true)
    @GetMapping("/{id}")
    public String detail(@AuthenticationPrincipal CatapultOAuth2User principal, @PathVariable UUID id, Model model) {
        Experiment exp = experimentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<String> eventKeys = eventRepository.findDistinctEventKeysByExperiment(exp);

        record ConversionStat(String eventKey, String variantKey, long conversions, long participants, StatisticsService.ZTestResult significance) {}
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
                    StatisticsService.ZTestResult sig = statisticsService.twoProportionZTest(controlConversions, controlN, variantConversions, variantN);
                    conversionStats.add(new ConversionStat(eventKey, variant.getKey(), variantConversions, variantN, sig));
                }
            }
        }

        record NpsStat(String variantKey, int npsScore, Double averageRaw) {}
        List<NpsStat> npsStats = exp.getVariants().stream().map(v -> {
            List<ExperimentFeedback> feedbacks = feedbackRepository.findByExperimentAndVariant(exp, v);
            int[] scores = feedbacks.stream().mapToInt(ExperimentFeedback::getNpsScore).toArray();
            Double avg = feedbackRepository.findAverageNpsByExperimentAndVariant(exp, v);
            return new NpsStat(v.getKey(), statisticsService.npsScore(scores), avg);
        }).toList();

        boolean hasManualRule = exp.getRules().stream()
            .anyMatch(r -> r.getRuleType() == ExperimentAssignmentRule.RuleType.MANUAL);

        model.addAttribute("experiment", exp);
        model.addAttribute("eventKeys", eventKeys);
        model.addAttribute("conversionStats", conversionStats);
        model.addAttribute("npsStats", npsStats);
        model.addAttribute("feedbackPage", feedbackRepository.findByExperiment(exp, PageRequest.of(0, 20)));
        model.addAttribute("hasManualRule", hasManualRule);
        model.addAttribute("participantPage", assignmentRepository.findByExperiment(exp, PageRequest.of(0, 20)));
        model.addAttribute("overrides", overrideRepository.findByExperimentOrderByPriorityAsc(exp));
        return "admin/experiment-detail";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() != Experiment.Status.DRAFT) {
            return REDIRECT_EXPERIMENTS_LIST;
        }
        exp.setStatus(Experiment.Status.ACTIVE);
        exp.setStartedAt(Instant.now());
        experimentRepository.save(exp);
        eventPublisher.publishEvent(new ExperimentActivatedEvent(this, exp.getId(), exp.getKey()));
        return REDIRECT_EXPERIMENT + id;
    }

    @PostMapping("/{id}/pause")
    public String pause(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() != Experiment.Status.ACTIVE) {
            return REDIRECT_EXPERIMENTS_LIST;
        }
        exp.setStatus(Experiment.Status.PAUSED);
        experimentRepository.save(exp);
        return REDIRECT_EXPERIMENT + id;
    }

    @PostMapping("/{id}/end")
    public String end(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() == Experiment.Status.DRAFT || exp.getStatus() == Experiment.Status.ENDED) {
            return REDIRECT_EXPERIMENTS_LIST;
        }
        exp.setStatus(Experiment.Status.ENDED);
        exp.setEndedAt(Instant.now());
        experimentRepository.save(exp);
        return REDIRECT_EXPERIMENT + id;
    }

    @PostMapping("/{id}/assign")
    public String assignUser(@PathVariable UUID id, @RequestParam String twitchUsername) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() != Experiment.Status.ACTIVE) {
            return REDIRECT_EXPERIMENT + id + "?assignError=notActive";
        }
        var user = userAccountRepository.findByTwitchUsername(twitchUsername);
        if (user.isEmpty()) {
            return REDIRECT_EXPERIMENT + id + "?assignError=userNotFound";
        }
        boolean alreadyAssigned = assignmentRepository.findByExperimentAndUser(exp, user.get()).isPresent();
        if (alreadyAssigned) {
            return REDIRECT_EXPERIMENT + id + "?assignError=alreadyAssigned";
        }
        if (!exp.getVariants().isEmpty()) {
            ExperimentVariant controlVariant = exp.getVariants().stream()
                .filter(ExperimentVariant::isControl).findFirst()
                .orElse(exp.getVariants().get(0));
            ExperimentAssignment assignment = new ExperimentAssignment();
            assignment.setExperiment(exp);
            assignment.setVariant(controlVariant);
            assignment.setUser(user.get());
            assignmentRepository.save(assignment);
        }
        return REDIRECT_EXPERIMENT + id;
    }

    // --- Variant weights -----------------------------------------------------

    @PostMapping("/{id}/variants/weights")
    @Transactional
    public String updateWeights(@PathVariable UUID id, @RequestParam Map<String, String> params) {
        Experiment exp = findOrThrow(id);
        for (ExperimentVariant v : exp.getVariants()) {
            String raw = params.get("weight_" + v.getId());
            if (raw == null) continue;
            try {
                int weight = Math.max(0, Integer.parseInt(raw.trim()));
                v.setWeight(weight);
                variantRepository.save(v);
            } catch (NumberFormatException ignored) {
                // Silent ignored
            }
        }
        return REDIRECT_EXPERIMENT + id;
    }

    // --- Rules ---------------------------------------------------------------

    @PostMapping("/{id}/rules")
    public String addRule(@PathVariable UUID id,
                          @RequestParam ExperimentAssignmentRule.RuleType ruleType,
                          @RequestParam(defaultValue = "0") int priority,
                          @RequestParam(required = false) Integer percentage,
                          @RequestParam(required = false) String attributeKey,
                          @RequestParam(required = false) String attributeOperator,
                          @RequestParam(required = false) String attributeValue) {
        Experiment exp = findOrThrow(id);
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setExperiment(exp);
        rule.setRuleType(ruleType);
        rule.setPriority(priority);
        if (ruleType == ExperimentAssignmentRule.RuleType.RANDOM) {
            rule.setPercentage(percentage);
        } else if (ruleType == ExperimentAssignmentRule.RuleType.ATTRIBUTE) {
            rule.setAttributeKey(attributeKey);
            rule.setAttributeOperator(attributeOperator);
            rule.setAttributeValue(attributeValue);
        }
        ruleRepository.save(rule);
        return REDIRECT_EXPERIMENT + id;
    }

    @PostMapping("/{id}/rules/{ruleId}/delete")
    public String deleteRule(@PathVariable UUID id, @PathVariable UUID ruleId) {
        findOrThrow(id);
        ExperimentAssignmentRule rule = ruleRepository.findById(ruleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (rule.getExperiment() == null || !rule.getExperiment().getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ruleRepository.delete(rule);
        return REDIRECT_EXPERIMENT + id;
    }

    // --- Overrides -----------------------------------------------------------

    @PostMapping("/{id}/overrides")
    public String addOverride(@PathVariable UUID id,
                              @RequestParam ExperimentOverride.OverrideType overrideType,
                              @RequestParam ExperimentOverride.OverrideAction action,
                              @RequestParam(defaultValue = "0") int priority,
                              @RequestParam(required = false) String twitchUsername,
                              @RequestParam(required = false) String attributeKey,
                              @RequestParam(required = false) String attributeOp,
                              @RequestParam(required = false) String attributeVal,
                              @RequestParam(required = false) UUID targetVariantId) {
        Experiment exp = findOrThrow(id);
        ExperimentOverride override = new ExperimentOverride();
        override.setExperiment(exp);
        override.setOverrideType(overrideType);
        override.setAction(action);
        override.setPriority(priority);
        if (overrideType == ExperimentOverride.OverrideType.USER) {
            if (twitchUsername == null || twitchUsername.isBlank()) {
                return REDIRECT_EXPERIMENT + id + "?overrideError=missingUsername";
            }
            UserAccount u = userAccountRepository.findByTwitchUsername(twitchUsername.trim()).orElse(null);
            if (u == null) {
                return REDIRECT_EXPERIMENT + id + "?overrideError=userNotFound";
            }
            override.setTargetUser(u);
        } else if (overrideType == ExperimentOverride.OverrideType.ATTRIBUTE) {
            override.setAttributeKey(attributeKey);
            override.setAttributeOp(attributeOp);
            override.setAttributeVal(attributeVal);
        }
        if (action == ExperimentOverride.OverrideAction.FORCE_VARIANT) {
            if (targetVariantId == null) {
                return REDIRECT_EXPERIMENT + id + "?overrideError=missingVariant";
            }
            Optional<ExperimentVariant> tv = variantRepository.findById(targetVariantId);
            if (tv.isEmpty()) {
                return REDIRECT_EXPERIMENT + id + "?overrideError=variantNotFound";
            }
            override.setTargetVariant(tv.get());
        }
        overrideRepository.save(override);
        return REDIRECT_EXPERIMENT + id;
    }

    @PostMapping("/{id}/overrides/{overrideId}/delete")
    public String deleteOverride(@PathVariable UUID id, @PathVariable UUID overrideId) {
        findOrThrow(id);
        ExperimentOverride override = overrideRepository.findById(overrideId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (override.getExperiment() == null || !override.getExperiment().getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        overrideRepository.delete(override);
        return REDIRECT_EXPERIMENT + id;
    }

    // --- Reassign ------------------------------------------------------------

    @PostMapping("/{id}/reassign")
    public String reassign(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        experimentService.reassignAll(exp);
        return REDIRECT_EXPERIMENT + id;
    }

    private Experiment findOrThrow(UUID id) {
        return experimentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
