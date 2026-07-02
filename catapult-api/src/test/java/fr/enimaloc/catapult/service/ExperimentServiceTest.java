package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTest {

    @Mock ExperimentRepository experimentRepository;
    @Mock ExperimentAssignmentRepository assignmentRepository;
    @Mock ExperimentEventRepository eventRepository;
    @Mock ExperimentOverrideRepository overrideRepository;
    @Mock fr.enimaloc.catapult.experiment.targeting.AttributeEvaluator attributeEvaluator;
    @InjectMocks ExperimentService experimentService;

    private UserAccount user;
    private Experiment experiment;
    private ExperimentVariant variantA;
    private ExperimentVariant variantB;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        variantA = new ExperimentVariant();
        variantA.setId(UUID.randomUUID());
        variantA.setKey("control");
        variantA.setWeight(50);

        variantB = new ExperimentVariant();
        variantB.setId(UUID.randomUUID());
        variantB.setKey("variant_a");
        variantB.setWeight(50);

        experiment = new Experiment();
        experiment.setId(UUID.randomUUID());
        experiment.setKey("test-exp");
        experiment.setStatus(Experiment.Status.ACTIVE);
        experiment.setVariants(List.of(variantA, variantB));
        experiment.setRules(new ArrayList<>());

        variantA.setExperiment(experiment);
        variantB.setExperiment(experiment);

        lenient().when(overrideRepository.findByExperimentOrderByPriorityAsc(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(experimentService, "self", experimentService);
    }

    @Test
    void getVariant_returnsEmpty_whenExperimentNotFound() {
        when(experimentRepository.findByKey("missing")).thenReturn(Optional.empty());
        assertThat(experimentService.getVariant(user, "missing")).isEmpty();
    }

    @Test
    void getVariant_returnsEmpty_whenExperimentNotActive() {
        experiment.setStatus(Experiment.Status.DRAFT);
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
    }

    @Test
    void getVariant_returnsExistingAssignment_whenAlreadyAssigned() {
        ExperimentAssignment existing = new ExperimentAssignment();
        existing.setVariant(variantA);
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.of(existing));

        assertThat(experimentService.getVariant(user, "test-exp")).contains(variantA);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void getVariant_assignsAndPersists_whenRandomRuleMatches() {
        ExperimentAssignmentRule randomRule = new ExperimentAssignmentRule();
        randomRule.setRuleType(ExperimentAssignmentRule.RuleType.RANDOM);
        randomRule.setPercentage(100); // always match
        randomRule.setExperiment(experiment);
        experiment.setRules(List.of(randomRule));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

        Optional<ExperimentVariant> result = experimentService.getVariant(user, "test-exp");

        assertThat(result).isPresent();
        assertThat(result.get().getKey()).isIn("control", "variant_a");
        verify(assignmentRepository).save(any(ExperimentAssignment.class));
    }

    @Test
    void getVariant_returnsEmpty_whenRandomRuleExcludes() {
        ExperimentAssignmentRule randomRule = new ExperimentAssignmentRule();
        randomRule.setRuleType(ExperimentAssignmentRule.RuleType.RANDOM);
        randomRule.setPercentage(0); // never match
        randomRule.setExperiment(experiment);
        experiment.setRules(List.of(randomRule));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void track_insertsEvent_whenUserIsAssigned() {
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setVariant(variantA);
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.of(assignment));

        experimentService.track(user, "test-exp", "page_view");

        ArgumentCaptor<ExperimentEvent> captor = ArgumentCaptor.forClass(ExperimentEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventKey()).isEqualTo("page_view");
        assertThat(captor.getValue().getVariant()).isEqualTo(variantA);
    }

    @Test
    void track_doesNothing_whenExperimentNotFound() {
        when(experimentRepository.findByKey("missing")).thenReturn(Optional.empty());
        experimentService.track(user, "missing", "page_view");
        verifyNoInteractions(eventRepository);
    }

    @Test
    void track_doesNothing_whenExperimentNotActive() {
        experiment.setStatus(Experiment.Status.PAUSED);
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        experimentService.track(user, "test-exp", "page_view");
        verifyNoInteractions(eventRepository);
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void track_doesNothing_whenUserNotAssigned() {
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

        experimentService.track(user, "test-exp", "page_view");
        verifyNoInteractions(eventRepository);
    }

    @Test
    void getVariant_returnsEmpty_whenNoRules() {
        // experiment has no rules → always returns empty (no auto-assign)
        experiment.setRules(new ArrayList<>());
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
    }

    @Test
    void getVariant_returnsEmpty_whenOnlyManualRule() {
        ExperimentAssignmentRule manualRule = new ExperimentAssignmentRule();
        manualRule.setRuleType(ExperimentAssignmentRule.RuleType.MANUAL);
        manualRule.setExperiment(experiment);
        experiment.setRules(List.of(manualRule));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void getVariant_assignsUser_whenAttributeRuleMatches() {
        // user has no steam (steamId == null), rule checks has_steam eq 0
        user.setSteamId(null);
        ExperimentAssignmentRule attrRule = new ExperimentAssignmentRule();
        attrRule.setRuleType(ExperimentAssignmentRule.RuleType.ATTRIBUTE);
        attrRule.setAttributeKey("has_steam");
        attrRule.setAttributeOperator("eq");
        attrRule.setAttributeValue("0.0");
        attrRule.setExperiment(experiment);
        experiment.setRules(List.of(attrRule));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(attributeEvaluator.matches(user, "has_steam", "eq", "0.0")).thenReturn(true);

        Optional<ExperimentVariant> result = experimentService.getVariant(user, "test-exp");
        assertThat(result).isPresent();
        verify(assignmentRepository).save(any(ExperimentAssignment.class));
    }

    @Test
    void getVariant_returnsEmpty_whenAttributeRuleDoesNotMatch() {
        // user has steam, rule expects has_steam eq 0 → no match
        user.setSteamId("12345");
        ExperimentAssignmentRule attrRule = new ExperimentAssignmentRule();
        attrRule.setRuleType(ExperimentAssignmentRule.RuleType.ATTRIBUTE);
        attrRule.setAttributeKey("has_steam");
        attrRule.setAttributeOperator("eq");
        attrRule.setAttributeValue("0.0");
        attrRule.setExperiment(experiment);
        experiment.setRules(List.of(attrRule));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(attributeEvaluator.matches(user, "has_steam", "eq", "0.0")).thenReturn(false);

        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void getActiveAssignments_delegatesToRepository() {
        List<ExperimentAssignment> expected = List.of(new ExperimentAssignment());
        when(assignmentRepository.findActiveByUser(user)).thenReturn(expected);
        assertThat(experimentService.getActiveAssignments(user)).isEqualTo(expected);
    }

    // ---- helpers overrides -------------------------------------------------

    private ExperimentOverride userOverride(ExperimentOverride.OverrideAction action, UserAccount target) {
        ExperimentOverride o = new ExperimentOverride();
        o.setOverrideType(ExperimentOverride.OverrideType.USER);
        o.setAction(action);
        o.setTargetUser(target);
        o.setExperiment(experiment);
        return o;
    }

    private ExperimentOverride variantOverride(UserAccount target, ExperimentVariant forced) {
        ExperimentOverride o = userOverride(ExperimentOverride.OverrideAction.FORCE_VARIANT, target);
        o.setTargetVariant(forced);
        return o;
    }

    private ExperimentAssignmentRule randomRule(int pct) {
        ExperimentAssignmentRule r = new ExperimentAssignmentRule();
        r.setRuleType(ExperimentAssignmentRule.RuleType.RANDOM);
        r.setPercentage(pct);
        r.setExperiment(experiment);
        return r;
    }

    // ---- override tests ----------------------------------------------------

    @Test
    void getVariant_excludesUser_whenForceExcludeOverrideMatches() {
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(overrideRepository.findByExperimentOrderByPriorityAsc(experiment))
            .thenReturn(List.of(userOverride(ExperimentOverride.OverrideAction.FORCE_EXCLUDE, user)));

        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void getVariant_forcesVariant_whenForceVariantOverrideMatches() {
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(overrideRepository.findByExperimentOrderByPriorityAsc(experiment))
            .thenReturn(List.of(variantOverride(user, variantB)));

        Optional<ExperimentVariant> result = experimentService.getVariant(user, "test-exp");

        assertThat(result).contains(variantB);
        verify(assignmentRepository).save(any(ExperimentAssignment.class));
    }

    @Test
    void getVariant_includesUser_whenForceIncludeOverrideMatches_evenWithoutRules() {
        // No rules configured — normally returns empty. Override forces inclusion.
        experiment.setRules(new ArrayList<>());
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(overrideRepository.findByExperimentOrderByPriorityAsc(experiment))
            .thenReturn(List.of(userOverride(ExperimentOverride.OverrideAction.FORCE_INCLUDE, user)));

        assertThat(experimentService.getVariant(user, "test-exp")).isPresent();
        verify(assignmentRepository).save(any(ExperimentAssignment.class));
    }

    @Test
    void getVariant_firstOverrideWins_whenMultipleMatch() {
        // priority 0 = FORCE_EXCLUDE, priority 1 = FORCE_INCLUDE — exclude must win
        ExperimentOverride exclude = userOverride(ExperimentOverride.OverrideAction.FORCE_EXCLUDE, user);
        exclude.setPriority(0);
        ExperimentOverride include = userOverride(ExperimentOverride.OverrideAction.FORCE_INCLUDE, user);
        include.setPriority(1);

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(overrideRepository.findByExperimentOrderByPriorityAsc(experiment))
            .thenReturn(List.of(exclude, include));

        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void getVariant_continuesNormally_whenNoOverrideMatches() {
        // Override targets a different user — should not affect current user
        UserAccount otherUser = new UserAccount();
        otherUser.setId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        experiment.setRules(List.of(randomRule(100)));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(overrideRepository.findByExperimentOrderByPriorityAsc(experiment))
            .thenReturn(List.of(userOverride(ExperimentOverride.OverrideAction.FORCE_EXCLUDE, otherUser)));

        assertThat(experimentService.getVariant(user, "test-exp")).isPresent();
        verify(assignmentRepository).save(any(ExperimentAssignment.class));
    }

    @Test
    void reassignAll_deletesAllAssignmentsAndReassigns() {
        ExperimentAssignment a1 = new ExperimentAssignment();
        a1.setUser(user);
        experiment.setRules(List.of(randomRule(100)));

        when(assignmentRepository.findAllByExperiment(experiment)).thenReturn(List.of(a1));
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(overrideRepository.findByExperimentOrderByPriorityAsc(experiment)).thenReturn(List.of());

        experimentService.reassignAll(experiment);

        verify(assignmentRepository).deleteAllByExperiment(experiment);
        verify(assignmentRepository).save(any(ExperimentAssignment.class));
    }

    // --- rollout gate tests ---

    @Test
    void getVariant_returnsEmpty_whenRolloutPercentageIsZero() {
        experiment.setRolloutPercentage(0);
        experiment.setRules(List.of(randomRule(100)));
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void getVariant_assignsUser_whenRolloutPercentageIsHundred() {
        experiment.setRolloutPercentage(100);
        experiment.setRules(List.of(randomRule(100)));
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

        assertThat(experimentService.getVariant(user, "test-exp")).isPresent();
        verify(assignmentRepository).save(any(ExperimentAssignment.class));
    }

    @Test
    void getVariant_bypassesRolloutGate_whenForceIncludeOverridePresent() {
        experiment.setRolloutPercentage(0);
        experiment.setRules(new ArrayList<>());
        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(overrideRepository.findByExperimentOrderByPriorityAsc(experiment))
            .thenReturn(List.of(userOverride(ExperimentOverride.OverrideAction.FORCE_INCLUDE, user)));

        assertThat(experimentService.getVariant(user, "test-exp")).isPresent();
    }

    // --- isRolledOut tests ---

    @Test
    void isRolledOut_returnsTrue_whenUserHasNonControlAssignment() {
        variantB.setControl(false);
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(variantB);
        when(assignmentRepository.findActiveByUser(user)).thenReturn(List.of(assignment));

        assertThat(experimentService.isRolledOut(user, "test-exp")).isTrue();
    }

    @Test
    void isRolledOut_returnsFalse_whenUserHasControlAssignment() {
        variantA.setControl(true);
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(variantA);
        when(assignmentRepository.findActiveByUser(user)).thenReturn(List.of(assignment));

        assertThat(experimentService.isRolledOut(user, "test-exp")).isFalse();
    }

    @Test
    void isRolledOut_returnsFalse_whenUserHasNoAssignment() {
        when(assignmentRepository.findActiveByUser(user)).thenReturn(List.of());
        assertThat(experimentService.isRolledOut(user, "test-exp")).isFalse();
    }

    // --- ensureExists tests ---

    @Test
    void ensureExists_savesExperimentWithVariants_whenKeyIsNew() {
        when(experimentRepository.findByKey("new-exp")).thenReturn(Optional.empty());

        experimentService.ensureExists("new-exp", "green");

        ArgumentCaptor<Experiment> captor = ArgumentCaptor.forClass(Experiment.class);
        verify(experimentRepository).save(captor.capture());
        Experiment saved = captor.getValue();
        assertThat(saved.getKey()).isEqualTo("new-exp");
        assertThat(saved.getStatus()).isEqualTo(Experiment.Status.DRAFT);
        assertThat(saved.getVariants()).hasSize(2);
        assertThat(saved.getVariants()).anyMatch(v -> "control".equals(v.getKey()) && v.isControl());
        assertThat(saved.getVariants()).anyMatch(v -> "green".equals(v.getKey()) && !v.isControl());
    }

    @Test
    void ensureExists_doesNotSave_whenKeyAlreadyKnown() {
        experimentService.markKnown("existing-exp");

        experimentService.ensureExists("existing-exp", "green");

        verify(experimentRepository, never()).save(any());
        verify(experimentRepository, never()).findByKey(any());
    }

    @Test
    void ensureExists_doesNotSave_whenExperimentAlreadyInDb() {
        when(experimentRepository.findByKey("db-exp")).thenReturn(Optional.of(experiment));

        experimentService.ensureExists("db-exp");

        verify(experimentRepository, never()).save(any());
    }

    @Test
    void ensureExists_createsOnlyControlVariant_whenNoHintsProvided() {
        when(experimentRepository.findByKey("flag-exp")).thenReturn(Optional.empty());

        experimentService.ensureExists("flag-exp");

        ArgumentCaptor<Experiment> captor = ArgumentCaptor.forClass(Experiment.class);
        verify(experimentRepository).save(captor.capture());
        assertThat(captor.getValue().getVariants()).hasSize(1);
        assertThat(captor.getValue().getVariants().get(0).isControl()).isTrue();
    }

    @Test
    void markKnown_preventsFurtherDbLookup() {
        experimentService.markKnown("seeded-exp");
        experimentService.ensureExists("seeded-exp", "v1");
        verifyNoInteractions(experimentRepository);
    }

    @Test
    void ensureExists_swallowsDataIntegrityViolationException_onRaceCondition() {
        when(experimentRepository.findByKey("race-exp")).thenReturn(Optional.empty());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("unique"))
            .when(experimentRepository).save(any());

        assertThatNoException().isThrownBy(() -> experimentService.ensureExists("race-exp"));
    }

    @Test
    void groupRule_matches_whenEvaluatorTrue() {
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setRuleType(ExperimentAssignmentRule.RuleType.GROUP);
        rule.setAttributeOperator("in");
        rule.setAttributeValue("beta");
        rule.setExperiment(experiment);
        experiment.setRules(new java.util.ArrayList<>(List.of(rule)));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(attributeEvaluator.matches(user, "group", "in", "beta")).thenReturn(true);

        assertThat(experimentService.getVariant(user, "test-exp")).isPresent();
    }

    @Test
    void experimentRule_usesPrefixedKey() {
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setRuleType(ExperimentAssignmentRule.RuleType.EXPERIMENT);
        rule.setAttributeKey("darkmode");
        rule.setAttributeOperator("eq");
        rule.setAttributeValue("on");
        rule.setExperiment(experiment);
        experiment.setRules(new java.util.ArrayList<>(List.of(rule)));

        when(experimentRepository.findByKey("test-exp")).thenReturn(Optional.of(experiment));
        when(assignmentRepository.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());
        when(attributeEvaluator.matches(user, "experiment:darkmode", "eq", "on")).thenReturn(false);

        assertThat(experimentService.getVariant(user, "test-exp")).isEmpty();
    }
}
