package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AssignRequest;
import fr.enimaloc.catapult.common.dto.admin.NpsStat;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideDto;
import fr.enimaloc.catapult.common.dto.experiment.MoveAssignmentRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRebalanceDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentStatus;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentVariantDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule;
import fr.enimaloc.catapult.domain.experiment.ExperimentFeedback;
import fr.enimaloc.catapult.domain.experiment.ExperimentOverride;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.event.ExperimentActivatedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRuleRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentEventRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentFeedbackRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentVariantRepository;
import fr.enimaloc.catapult.service.StatisticsService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.experiment.ExperimentRebalancer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/experiments")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
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
    private final ExperimentRebalancer rebalancer;

    @Transactional(readOnly = true)
    @GetMapping
    public List<ExperimentSummaryDto> list() {
        return experimentRepository.findAll().stream()
                .sorted(Comparator.comparing(Experiment::getKey))
                .map(e -> new ExperimentSummaryDto(e.getId(), e.getKey(), e.getName(),
                        ExperimentStatus.valueOf(e.getStatus().name()), variantDtos(e), e.getRules().size()))
                .toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/{id}/settings")
    public ExperimentSettingsDto settings(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        return new ExperimentSettingsDto(exp.getId(), exp.getKey(), exp.getName(), exp.getDescription(),
                ExperimentStatus.valueOf(exp.getStatus().name()), variantDtos(exp),
                exp.getRules().stream().map(r -> new ExperimentRuleDto(r.getId(), r.getRuleType().name(),
                        r.getPercentage(), r.getAttributeKey(), r.getAttributeOperator(), r.getAttributeValue())).toList(),
                overrideRepository.findByExperimentOrderByPriorityAsc(exp).stream().map(o -> new ExperimentOverrideDto(
                        o.getId(), o.getOverrideType().name(), o.getAction().name(), o.getPriority(),
                        o.getTargetUser() == null ? null : o.getTargetUser().getTwitchUsername(),
                        o.getAttributeKey(), o.getAttributeOp(), o.getAttributeVal(),
                        o.getTargetVariant() == null ? null : o.getTargetVariant().getId())).toList());
    }

    /** Variants in creation order (internalId); the sort is stable, so ties keep the entity's order. */
    private static List<ExperimentVariantDto> variantDtos(Experiment exp) {
        return exp.getVariants().stream()
                .sorted(Comparator.comparingInt(ExperimentVariant::getInternalId))
                .map(v -> new ExperimentVariantDto(v.getId(), v.getKey(), v.getName(), v.getWeight(), v.isControl()))
                .toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/{id}")
    public ExperimentDetailData detail(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        List<String> eventKeys = eventRepository.findDistinctEventKeysByExperiment(exp);
        boolean hasManualRule = exp.getRules().stream()
                .anyMatch(r -> r.getRuleType() == ExperimentAssignmentRule.RuleType.MANUAL);

        return new ExperimentDetailData(
                exp, eventKeys, conversionStats(exp, eventKeys), npsStats(exp),
                feedbackRepository.findByExperiment(exp, PageRequest.of(0, 20)).getContent(),
                hasManualRule,
                assignmentRepository.findByExperiment(exp, PageRequest.of(0, 20)).getContent(),
                overrideRepository.findByExperimentOrderByPriorityAsc(exp)
        );
    }

    /**
     * For each tracked event, how every non-control variant converts compared to the control
     * (two-proportion z-test). Needs at least two variants.
     */
    private List<ConversionStat> conversionStats(Experiment exp, List<String> eventKeys) {
        List<ConversionStat> stats = new ArrayList<>();
        if (exp.getVariants().size() < 2) {
            return stats;
        }
        ExperimentVariant control = controlVariant(exp);
        long controlN = assignmentRepository.countByExperimentAndVariant(exp, control);
        for (String eventKey : eventKeys) {
            long controlConversions = eventRepository.countByExperimentAndVariantAndEventKey(exp, control, eventKey);
            for (ExperimentVariant variant : exp.getVariants()) {
                if (variant.equals(control)) continue;
                long variantN = assignmentRepository.countByExperimentAndVariant(exp, variant);
                long variantConversions = eventRepository.countByExperimentAndVariantAndEventKey(exp, variant, eventKey);
                StatisticsService.ZTestResult significance = statisticsService.twoProportionZTest(
                        controlConversions, controlN, variantConversions, variantN);
                stats.add(new ConversionStat(eventKey, variant.getKey(), variantConversions, variantN, significance));
            }
        }
        return stats;
    }

    /** Each variant's NPS (from its feedback scores) and average score. */
    private List<NpsStat> npsStats(Experiment exp) {
        return exp.getVariants().stream().map(variant -> {
            int[] scores = feedbackRepository.findByExperimentAndVariant(exp, variant).stream()
                    .mapToInt(ExperimentFeedback::getNpsScore).toArray();
            Double average = feedbackRepository.findAverageNpsByExperimentAndVariant(exp, variant);
            return new NpsStat(variant.getKey(), statisticsService.npsScore(scores), average);
        }).toList();
    }

    /** The variant flagged as control, else the first one. */
    private static ExperimentVariant controlVariant(Experiment exp) {
        return exp.getVariants().stream()
                .filter(ExperimentVariant::isControl).findFirst()
                .orElse(exp.getVariants().getFirst());
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID id) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() == Experiment.Status.PAUSED) {
            // Resuming is not a first start: keep startedAt and stay silent, as the activation listeners
            // (metrics, mock data seeding) count and seed first starts only.
            exp.setStatus(Experiment.Status.ACTIVE);
            experimentRepository.save(exp);
            return;
        }
        if (exp.getStatus() != Experiment.Status.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment not in DRAFT or PAUSED status");
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
            ExperimentAssignment assignment = new ExperimentAssignment();
            assignment.setExperiment(exp);
            assignment.setVariant(controlVariant(exp));
            assignment.setUser(user);
            assignmentRepository.save(assignment);
        }
    }

    @PostMapping("/{id}/variants/weights")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateWeights(@PathVariable UUID id, @RequestBody Map<UUID, Integer> weights) {
        Experiment exp = findOrThrow(id);
        if (exp.getStatus() == Experiment.Status.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment ended");
        }
        Map<UUID, Integer> effective = effectiveWeights(exp, weights);
        for (ExperimentVariant v : exp.getVariants()) {
            if (weights.get(v.getId()) != null) {
                v.setWeight(effective.get(v.getId()));
                variantRepository.save(v);
            }
        }
        // Same transaction: the members follow the new weights, moving only those a variant now has too many of.
        rebalancer.apply(exp, rebalancer.plan(exp, effective));
    }

    /** What saving these weights would move, without saving or moving anything. */
    @PostMapping("/{id}/variants/weights/preview")
    @Transactional(readOnly = true)
    public ExperimentRebalanceDto previewWeights(@PathVariable UUID id, @RequestBody Map<UUID, Integer> weights) {
        Experiment exp = findOrThrow(id);
        return rebalancer.plan(exp, effectiveWeights(exp, weights)).toDto();
    }

    /** Each variant's weight once {@code proposed} is applied: clamped at 0, unlisted variants unchanged. */
    private static Map<UUID, Integer> effectiveWeights(Experiment exp, Map<UUID, Integer> proposed) {
        Map<UUID, Integer> effective = new HashMap<>();
        for (ExperimentVariant v : exp.getVariants()) {
            Integer weight = proposed.get(v.getId());
            effective.put(v.getId(), weight != null ? Math.max(0, weight) : v.getWeight());
        }
        return effective;
    }

    @PostMapping("/{id}/rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addRule(@PathVariable UUID id, @RequestBody AddRuleRequest body) {
        Experiment exp = findOrThrow(id);
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setExperiment(exp);
        rule.setRuleType(body.ruleType());
        rule.setPriority(body.priority());
        switch (body.ruleType()) {
            case RANDOM -> rule.setPercentage(body.percentage());
            case ATTRIBUTE, GROUP, EXPERIMENT -> {
                rule.setAttributeKey(body.attributeKey());
                rule.setAttributeOperator(body.attributeOperator());
                rule.setAttributeValue(body.attributeValue());
            }
            case MANUAL -> { /* no extra fields */ }
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
            // A variant of another experiment is as good as unknown: forcing it would leak across experiments.
            if (tv.get().getExperiment() == null || !tv.get().getExperiment().getId().equals(exp.getId())) {
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

    private static final int PARTICIPANTS_PAGE_SIZE = 20;

    /**
     * An experiment's participants, one page at a time (there can be many), plus how many each variant holds
     * whatever the filter. {@code variant} narrows to one variant, {@code q} to usernames containing it.
     */
    @Transactional(readOnly = true)
    @GetMapping("/{id}/assignments")
    public ExperimentAssignmentsDto assignments(@PathVariable UUID id,
                                                @RequestParam(required = false) UUID variant,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page) {
        Experiment exp = findOrThrow(id);
        if (variant != null) {
            findVariantOf(exp, variant);
        }
        PageRequest paging = PageRequest.of(Math.max(0, page), PARTICIPANTS_PAGE_SIZE);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Page<ExperimentAssignment> found;
        if (needle.isEmpty()) {
            found = variant == null ? assignmentRepository.searchAll(exp, paging)
                    : assignmentRepository.searchByVariant(exp, variant, paging);
        } else {
            String pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            found = variant == null ? assignmentRepository.searchByUsername(exp, pattern, paging)
                    : assignmentRepository.searchByVariantAndUsername(exp, variant, pattern, paging);
        }

        Set<UUID> forced = new HashSet<>();
        for (ExperimentOverride o : overrideRepository.findByExperimentOrderByPriorityAsc(exp)) {
            if (o.getOverrideType() == ExperimentOverride.OverrideType.USER
                    && o.getAction() == ExperimentOverride.OverrideAction.FORCE_VARIANT && o.getTargetUser() != null) {
                forced.add(o.getTargetUser().getId());
            }
        }
        List<ExperimentAssignmentDto> rows = found.getContent().stream()
                .map(a -> new ExperimentAssignmentDto(a.getUser().getId(), a.getUser().getTwitchUsername(),
                        a.getVariant().getId(), a.getAssignedAt(), forced.contains(a.getUser().getId())))
                .toList();

        Map<UUID, Long> counts = new HashMap<>();
        exp.getVariants().forEach(v -> counts.put(v.getId(), 0L));
        for (Object[] row : assignmentRepository.countByVariant(exp)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return new ExperimentAssignmentsDto(
                new AdminPage<>(rows, found.getNumber(), found.getTotalPages(), found.getTotalElements()), counts);
    }

    /**
     * Moves a participant to another variant: a USER FORCE_VARIANT override keeps them there on later
     * evaluations, and their assignment is corrected now so the list shows it at once.
     */
    @PostMapping("/{id}/assignments/{userId}/variant")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveAssignment(@PathVariable UUID id, @PathVariable UUID userId,
                               @RequestBody MoveAssignmentRequest body) {
        Experiment exp = findOrThrow(id);
        if (body == null || body.variantId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing variantId");
        }
        if (exp.getStatus() == Experiment.Status.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment ended");
        }
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        ExperimentVariant target = findVariantOf(exp, body.variantId());

        List<ExperimentOverride> mine = overrideRepository.findByExperimentOrderByPriorityAsc(exp).stream()
                .filter(o -> o.getOverrideType() == ExperimentOverride.OverrideType.USER
                        && o.getTargetUser() != null && o.getTargetUser().getId().equals(userId))
                .toList();
        // Keep one override (preferably the FORCE_VARIANT one) and drop the others: a lower-priority
        // FORCE_EXCLUDE left behind would still win over the move.
        ExperimentOverride keep = mine.stream()
                .filter(o -> o.getAction() == ExperimentOverride.OverrideAction.FORCE_VARIANT).findFirst()
                .orElse(mine.isEmpty() ? null : mine.getFirst());
        mine.stream().filter(o -> o != keep).forEach(overrideRepository::delete);
        ExperimentOverride override = keep != null ? keep : new ExperimentOverride();
        override.setExperiment(exp);
        override.setOverrideType(ExperimentOverride.OverrideType.USER);
        override.setTargetUser(user);
        override.setAction(ExperimentOverride.OverrideAction.FORCE_VARIANT);
        override.setTargetVariant(target);
        override.setPriority(0);
        overrideRepository.save(override);

        ExperimentAssignment assignment = assignmentRepository.findByExperimentAndUser(exp, user)
                .orElseGet(() -> {
                    ExperimentAssignment created = new ExperimentAssignment();
                    created.setExperiment(exp);
                    created.setUser(user);
                    return created;
                });
        assignment.setVariant(target);
        assignmentRepository.save(assignment);
    }

    /** A variant of this experiment; unknown or belonging to another experiment is a 404. */
    private ExperimentVariant findVariantOf(Experiment exp, UUID variantId) {
        return variantRepository.findById(variantId)
                .filter(v -> v.getExperiment() != null && v.getExperiment().getId().equals(exp.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found"));
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
