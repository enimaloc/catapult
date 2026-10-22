package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AssignRequest;
import fr.enimaloc.catapult.common.dto.admin.NpsStat;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideDto;
import fr.enimaloc.catapult.common.dto.experiment.MoveAssignmentRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAdminExperimentsControllerTest {

    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentEventRepository events = mock(ExperimentEventRepository.class);
    private final ExperimentFeedbackRepository feedback = mock(ExperimentFeedbackRepository.class);
    private final ExperimentAssignmentRepository assignments = mock(ExperimentAssignmentRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final StatisticsService statistics = new StatisticsService();
    private final ExperimentOverrideRepository overrides = mock(ExperimentOverrideRepository.class);
    private final ExperimentAssignmentRuleRepository rules = mock(ExperimentAssignmentRuleRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final ExperimentService experimentService = mock(ExperimentService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private final ApiAdminExperimentsController controller = new ApiAdminExperimentsController(experiments, events,
            feedback, assignments, users, statistics, overrides, rules, variants, experimentService, publisher);

    private Experiment experiment;
    private ExperimentVariant control;
    private ExperimentVariant treatment;

    private static ExperimentVariant variant(String key, boolean isControl) {
        ExperimentVariant variant = new ExperimentVariant();
        variant.setId(UUID.randomUUID());
        variant.setKey(key);
        variant.setControl(isControl);
        return variant;
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @BeforeEach
    void setUp() {
        experiment = new Experiment();
        experiment.setId(UUID.randomUUID());
        experiment.setKey("new-dashboard");
        treatment = variant("new", false);
        treatment.setInternalId(2);
        control = variant("old", true);
        control.setInternalId(1);
        experiment.setVariants(new java.util.ArrayList<>(List.of(treatment, control)));
        when(experiments.findById(experiment.getId())).thenReturn(Optional.of(experiment));
        when(feedback.findByExperiment(eq(experiment), any())).thenReturn(new PageImpl<>(List.of()));
        when(assignments.findByExperiment(eq(experiment), any())).thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void unknownExperiment_isNotFound() {
        assertStatus(() -> controller.detail(UUID.randomUUID()), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.reassign(UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }

    @Test
    void list_summarisesEachExperiment() {
        when(experiments.findAll()).thenReturn(List.of(experiment));

        assertThat(controller.list()).singleElement().satisfies(s -> {
            assertThat(s.key()).isEqualTo("new-dashboard");
            assertThat(s.status()).isEqualTo(ExperimentStatus.DRAFT);
            assertThat(s.variants()).extracting(ExperimentVariantDto::key).containsExactly("old", "new");
            assertThat(s.variants().getFirst().control()).isTrue();
            assertThat(s.ruleCount()).isZero();
        });
    }

    @Test
    void list_isSortedByKey() {
        Experiment another = new Experiment();
        another.setId(UUID.randomUUID());
        another.setKey("a-first");
        when(experiments.findAll()).thenReturn(List.of(experiment, another));

        assertThat(controller.list()).extracting(s -> s.key()).containsExactly("a-first", "new-dashboard");
    }

    @Test
    void settings_carriesRulesAndOverrides() {
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setId(UUID.randomUUID());
        rule.setRuleType(ExperimentAssignmentRule.RuleType.RANDOM);
        rule.setPercentage(25);
        experiment.setRules(new java.util.ArrayList<>(List.of(rule)));
        UserAccount target = new UserAccount();
        target.setTwitchUsername("viewer");
        ExperimentOverride userOverride = new ExperimentOverride();
        userOverride.setId(UUID.randomUUID());
        userOverride.setOverrideType(ExperimentOverride.OverrideType.USER);
        userOverride.setAction(ExperimentOverride.OverrideAction.FORCE_INCLUDE);
        userOverride.setPriority(1);
        userOverride.setTargetUser(target);
        ExperimentOverride forced = new ExperimentOverride();
        forced.setId(UUID.randomUUID());
        forced.setOverrideType(ExperimentOverride.OverrideType.ATTRIBUTE);
        forced.setAction(ExperimentOverride.OverrideAction.FORCE_VARIANT);
        forced.setPriority(2);
        forced.setAttributeKey("group");
        forced.setAttributeOp("=");
        forced.setAttributeVal("beta");
        forced.setTargetVariant(treatment);
        when(overrides.findByExperimentOrderByPriorityAsc(experiment)).thenReturn(List.of(userOverride, forced));

        var settings = controller.settings(experiment.getId());

        assertThat(settings.key()).isEqualTo("new-dashboard");
        assertThat(settings.variants()).extracting(ExperimentVariantDto::key).containsExactly("old", "new");
        assertThat(settings.rules()).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("RANDOM");
            assertThat(r.percentage()).isEqualTo(25);
        });
        assertThat(settings.overrides()).extracting(ExperimentOverrideDto::type, ExperimentOverrideDto::action,
                        ExperimentOverrideDto::twitchUsername, ExperimentOverrideDto::targetVariantId)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("USER", "FORCE_INCLUDE", "viewer", null),
                        org.assertj.core.api.Assertions.tuple("ATTRIBUTE", "FORCE_VARIANT", null, treatment.getId()));
        assertThat(settings.overrides().get(1).attributeVal()).isEqualTo("beta");
    }

    @Test
    void settings_ofAnUnknownExperiment_isNotFound() {
        assertStatus(() -> controller.settings(UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }

    @Nested
    class Detail {
        @Test
        void comparesEachVariantToTheControl() {
            when(events.findDistinctEventKeysByExperiment(experiment)).thenReturn(List.of("clicked"));
            when(assignments.countByExperimentAndVariant(experiment, control)).thenReturn(100L);
            when(assignments.countByExperimentAndVariant(experiment, treatment)).thenReturn(100L);
            when(events.countByExperimentAndVariantAndEventKey(experiment, control, "clicked")).thenReturn(10L);
            when(events.countByExperimentAndVariantAndEventKey(experiment, treatment, "clicked")).thenReturn(30L);

            var detail = controller.detail(experiment.getId());

            assertThat(detail.eventKeys()).containsExactly("clicked");
            assertThat(detail.conversionStats()).singleElement().satisfies(stat -> {
                assertThat(stat.variantKey()).isEqualTo("new");
                assertThat(stat.conversions()).isEqualTo(30);
                assertThat(stat.participants()).isEqualTo(100);
                assertThat(stat.significance().significant()).isTrue();
            });
        }

        @Test
        void firstVariantIsTheControlWhenNoneIsFlagged() {
            control.setControl(false);
            when(events.findDistinctEventKeysByExperiment(experiment)).thenReturn(List.of("clicked"));

            var detail = controller.detail(experiment.getId());

            assertThat(detail.conversionStats()).extracting(ApiAdminExperimentsController.ConversionStat::variantKey)
                    .containsExactly("old");
        }

        @Test
        void singleVariant_hasNoConversionStats() {
            experiment.setVariants(new java.util.ArrayList<>(List.of(control)));
            when(events.findDistinctEventKeysByExperiment(experiment)).thenReturn(List.of("clicked"));

            assertThat(controller.detail(experiment.getId()).conversionStats()).isEmpty();
        }

        @Test
        void npsPerVariant() {
            ExperimentFeedback promoter = new ExperimentFeedback();
            promoter.setNpsScore(10);
            when(feedback.findByExperimentAndVariant(experiment, treatment)).thenReturn(List.of(promoter));
            when(feedback.findAverageNpsByExperimentAndVariant(experiment, treatment)).thenReturn(10.0);

            var detail = controller.detail(experiment.getId());

            assertThat(detail.npsStats()).contains(new NpsStat("new", 100, 10.0));
        }

        @Test
        void flagsManualAssignment() {
            ExperimentAssignmentRule manual = new ExperimentAssignmentRule();
            manual.setRuleType(ExperimentAssignmentRule.RuleType.MANUAL);
            experiment.setRules(new java.util.ArrayList<>(List.of(manual)));

            assertThat(controller.detail(experiment.getId()).hasManualRule()).isTrue();
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void activate_onlyFromDraft_andAnnounced() {
            controller.activate(experiment.getId());

            assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.ACTIVE);
            assertThat(experiment.getStartedAt()).isNotNull();
            verify(experiments).save(experiment);
            ArgumentCaptor<ExperimentActivatedEvent> event = ArgumentCaptor.forClass(ExperimentActivatedEvent.class);
            verify(publisher).publishEvent(event.capture());
            assertThat(event.getValue().getExperimentKey()).isEqualTo("new-dashboard");

            assertStatus(() -> controller.activate(experiment.getId()), HttpStatus.CONFLICT);
        }

        @Test
        void activate_resumesAPausedExperiment_keepingItsStart_unannounced() {
            java.time.Instant started = java.time.Instant.parse("2026-10-01T00:00:00Z");
            experiment.setStatus(Experiment.Status.PAUSED);
            experiment.setStartedAt(started);

            controller.activate(experiment.getId());

            assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.ACTIVE);
            assertThat(experiment.getStartedAt()).isEqualTo(started);
            verify(experiments).save(experiment);
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        void activate_refusedOnceEnded() {
            experiment.setStatus(Experiment.Status.ENDED);

            assertStatus(() -> controller.activate(experiment.getId()), HttpStatus.CONFLICT);
        }

        @Test
        void pause_onlyWhileActive() {
            assertStatus(() -> controller.pause(experiment.getId()), HttpStatus.CONFLICT);

            experiment.setStatus(Experiment.Status.ACTIVE);
            controller.pause(experiment.getId());
            assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.PAUSED);
        }

        @Test
        void end_notFromDraftNorTwice() {
            assertStatus(() -> controller.end(experiment.getId()), HttpStatus.CONFLICT);

            experiment.setStatus(Experiment.Status.PAUSED);
            controller.end(experiment.getId());
            assertThat(experiment.getStatus()).isEqualTo(Experiment.Status.ENDED);
            assertThat(experiment.getEndedAt()).isNotNull();

            assertStatus(() -> controller.end(experiment.getId()), HttpStatus.CONFLICT);
        }

        @Test
        void reassign_delegates() {
            controller.reassign(experiment.getId());

            verify(experimentService).reassignAll(experiment);
        }
    }

    @Nested
    class ManualAssignment {
        private final UserAccount user = new UserAccount();

        @BeforeEach
        void activeExperiment() {
            experiment.setStatus(Experiment.Status.ACTIVE);
            when(users.findByTwitchUsername("viewer")).thenReturn(Optional.of(user));
        }

        @Test
        void assignsTheControlVariant() {
            controller.assignUser(experiment.getId(), new AssignRequest("viewer"));

            ArgumentCaptor<ExperimentAssignment> saved = ArgumentCaptor.forClass(ExperimentAssignment.class);
            verify(assignments).save(saved.capture());
            assertThat(saved.getValue().getVariant()).isSameAs(control);
            assertThat(saved.getValue().getUser()).isSameAs(user);
        }

        @Test
        void refusedUnlessActive_forUnknownOrAlreadyAssignedUsers() {
            experiment.setStatus(Experiment.Status.PAUSED);
            assertStatus(() -> controller.assignUser(experiment.getId(), new AssignRequest("viewer")), HttpStatus.CONFLICT);

            experiment.setStatus(Experiment.Status.ACTIVE);
            assertStatus(() -> controller.assignUser(experiment.getId(), new AssignRequest("ghost")), HttpStatus.NOT_FOUND);

            when(assignments.findByExperimentAndUser(experiment, user)).thenReturn(Optional.of(new ExperimentAssignment()));
            assertStatus(() -> controller.assignUser(experiment.getId(), new AssignRequest("viewer")), HttpStatus.CONFLICT);
        }

        @Test
        void experimentWithoutVariants_assignsNothing() {
            experiment.setVariants(new java.util.ArrayList<>());

            controller.assignUser(experiment.getId(), new AssignRequest("viewer"));

            verify(assignments, never()).save(any());
        }
    }

    @Test
    void updateWeights_clampsNegativesAndSkipsUnlisted() {
        controller.updateWeights(experiment.getId(), Map.of(treatment.getId(), -5));

        assertThat(treatment.getWeight()).isZero();
        verify(variants).save(treatment);
        verify(variants, never()).save(control);
    }

    @Nested
    class Rules {
        private ExperimentAssignmentRule saved() {
            ArgumentCaptor<ExperimentAssignmentRule> captor = ArgumentCaptor.forClass(ExperimentAssignmentRule.class);
            verify(rules).save(captor.capture());
            return captor.getValue();
        }

        @Test
        void randomRuleKeepsItsPercentage() {
            controller.addRule(experiment.getId(), new ApiAdminExperimentsController.AddRuleRequest(
                    ExperimentAssignmentRule.RuleType.RANDOM, 1, 25, "ignored", "=", "x"));

            ExperimentAssignmentRule rule = saved();
            assertThat(rule.getPercentage()).isEqualTo(25);
            assertThat(rule.getAttributeKey()).isNull();
            assertThat(rule.getExperiment()).isSameAs(experiment);
        }

        @Test
        void attributeRuleKeepsItsCondition() {
            controller.addRule(experiment.getId(), new ApiAdminExperimentsController.AddRuleRequest(
                    ExperimentAssignmentRule.RuleType.GROUP, 2, null, "group", "in", "beta"));

            ExperimentAssignmentRule rule = saved();
            assertThat(rule.getAttributeKey()).isEqualTo("group");
            assertThat(rule.getAttributeOperator()).isEqualTo("in");
            assertThat(rule.getAttributeValue()).isEqualTo("beta");
            assertThat(rule.getPriority()).isEqualTo(2);
        }

        @Test
        void manualRuleHasNoExtraFields() {
            controller.addRule(experiment.getId(), new ApiAdminExperimentsController.AddRuleRequest(
                    ExperimentAssignmentRule.RuleType.MANUAL, 0, 50, "k", "=", "v"));

            assertThat(saved().getPercentage()).isNull();
        }

        @Test
        void deleteOnlyFromItsOwnExperiment() {
            ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
            rule.setExperiment(experiment);
            UUID ruleId = UUID.randomUUID();
            when(rules.findById(ruleId)).thenReturn(Optional.of(rule));

            controller.deleteRule(experiment.getId(), ruleId);
            verify(rules).delete(rule);

            Experiment other = new Experiment();
            other.setId(UUID.randomUUID());
            rule.setExperiment(other);
            assertStatus(() -> controller.deleteRule(experiment.getId(), ruleId), HttpStatus.NOT_FOUND);
            assertStatus(() -> controller.deleteRule(experiment.getId(), UUID.randomUUID()), HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Overrides {
        private ApiAdminExperimentsController.AddOverrideRequest request(ExperimentOverride.OverrideType type,
                                                                         ExperimentOverride.OverrideAction action,
                                                                         String username, UUID variantId) {
            return new ApiAdminExperimentsController.AddOverrideRequest(type, action, 1, username, "k", "=", "v", variantId);
        }

        private ExperimentOverride saved() {
            ArgumentCaptor<ExperimentOverride> captor = ArgumentCaptor.forClass(ExperimentOverride.class);
            verify(overrides).save(captor.capture());
            return captor.getValue();
        }

        @Test
        void userOverride_targetsTheTrimmedUsername() {
            UserAccount user = new UserAccount();
            when(users.findByTwitchUsername("viewer")).thenReturn(Optional.of(user));

            controller.addOverride(experiment.getId(), request(ExperimentOverride.OverrideType.USER,
                    ExperimentOverride.OverrideAction.FORCE_INCLUDE, "  viewer ", null));

            assertThat(saved().getTargetUser()).isSameAs(user);
        }

        @Test
        void userOverride_needsAKnownUsername() {
            assertStatus(() -> controller.addOverride(experiment.getId(), request(ExperimentOverride.OverrideType.USER,
                    ExperimentOverride.OverrideAction.FORCE_INCLUDE, " ", null)), HttpStatus.BAD_REQUEST);
            assertStatus(() -> controller.addOverride(experiment.getId(), request(ExperimentOverride.OverrideType.USER,
                    ExperimentOverride.OverrideAction.FORCE_INCLUDE, "ghost", null)), HttpStatus.NOT_FOUND);
        }

        @Test
        void attributeOverride_forcingAVariant() {
            treatment.setExperiment(experiment);
            when(variants.findById(treatment.getId())).thenReturn(Optional.of(treatment));

            controller.addOverride(experiment.getId(), request(ExperimentOverride.OverrideType.ATTRIBUTE,
                    ExperimentOverride.OverrideAction.FORCE_VARIANT, null, treatment.getId()));

            ExperimentOverride override = saved();
            assertThat(override.getAttributeKey()).isEqualTo("k");
            assertThat(override.getTargetVariant()).isSameAs(treatment);
        }

        @Test
        void forcingAVariant_needsAKnownOne() {
            assertStatus(() -> controller.addOverride(experiment.getId(), request(ExperimentOverride.OverrideType.ATTRIBUTE,
                    ExperimentOverride.OverrideAction.FORCE_VARIANT, null, null)), HttpStatus.BAD_REQUEST);
            assertStatus(() -> controller.addOverride(experiment.getId(), request(ExperimentOverride.OverrideType.ATTRIBUTE,
                    ExperimentOverride.OverrideAction.FORCE_VARIANT, null, UUID.randomUUID())), HttpStatus.NOT_FOUND);
        }

        @Test
        void forcingAVariantOfAnotherExperiment_isNotFound() {
            ExperimentVariant foreign = variant("foreign", false);
            Experiment other = new Experiment();
            other.setId(UUID.randomUUID());
            foreign.setExperiment(other);
            when(variants.findById(foreign.getId())).thenReturn(Optional.of(foreign));

            assertStatus(() -> controller.addOverride(experiment.getId(), request(ExperimentOverride.OverrideType.ATTRIBUTE,
                    ExperimentOverride.OverrideAction.FORCE_VARIANT, null, foreign.getId())), HttpStatus.NOT_FOUND);
            verify(overrides, never()).save(any());
        }

        @Test
        void deleteOnlyFromItsOwnExperiment() {
            ExperimentOverride override = new ExperimentOverride();
            override.setExperiment(experiment);
            UUID overrideId = UUID.randomUUID();
            when(overrides.findById(overrideId)).thenReturn(Optional.of(override));

            controller.deleteOverride(experiment.getId(), overrideId);
            verify(overrides).delete(override);

            override.setExperiment(null);
            assertStatus(() -> controller.deleteOverride(experiment.getId(), overrideId), HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Participants {
        @BeforeEach
        void emptyPagesByDefault() {
            PageImpl<ExperimentAssignment> none = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
            when(assignments.searchAll(any(), any())).thenReturn(none);
            when(assignments.searchByVariant(any(), any(), any())).thenReturn(none);
            when(assignments.searchByUsername(any(), any(), any())).thenReturn(none);
            when(assignments.searchByVariantAndUsername(any(), any(), any(), any())).thenReturn(none);
        }

        private UserAccount member(String name) {
            UserAccount user = new UserAccount();
            user.setId(UUID.randomUUID());
            user.setTwitchUsername(name);
            return user;
        }

        private ExperimentAssignment assignment(UserAccount user, ExperimentVariant variant) {
            ExperimentAssignment a = new ExperimentAssignment();
            a.setExperiment(experiment);
            a.setUser(user);
            a.setVariant(variant);
            a.setAssignedAt(Instant.parse("2026-10-22T10:00:00Z"));
            return a;
        }

        private ExperimentOverride userOverride(UserAccount user, ExperimentOverride.OverrideAction action,
                                                ExperimentVariant target) {
            ExperimentOverride o = new ExperimentOverride();
            o.setExperiment(experiment);
            o.setOverrideType(ExperimentOverride.OverrideType.USER);
            o.setAction(action);
            o.setTargetUser(user);
            o.setTargetVariant(target);
            o.setPriority(3);
            return o;
        }

        private void stubSearch(UUID variantId, List<ExperimentAssignment> content) {
            PageImpl<ExperimentAssignment> page = new PageImpl<>(content, PageRequest.of(0, 20), content.size());
            if (variantId == null) {
                when(assignments.searchAll(eq(experiment), any())).thenReturn(page);
            } else {
                when(assignments.searchByVariant(eq(experiment), eq(variantId), any())).thenReturn(page);
            }
        }

        @Test
        void list_filtersOnAVariantAndMapsTheRows() {
            treatment.setExperiment(experiment);
            when(variants.findById(treatment.getId())).thenReturn(Optional.of(treatment));
            UserAccount alice = member("alice");
            stubSearch(treatment.getId(), List.of(assignment(alice, treatment)));

            ExperimentAssignmentsDto result = controller.assignments(experiment.getId(), treatment.getId(), null, 0);

            assertThat(result.page().content()).containsExactly(new ExperimentAssignmentDto(alice.getId(), "alice",
                    treatment.getId(), Instant.parse("2026-10-22T10:00:00Z"), false));
            assertThat(result.page().totalElements()).isEqualTo(1);
            verify(assignments).searchByVariant(experiment, treatment.getId(), PageRequest.of(0, 20));
        }

        @Test
        void list_trimsAndLowercasesTheSearch() {
            treatment.setExperiment(experiment);
            when(variants.findById(treatment.getId())).thenReturn(Optional.of(treatment));
            controller.assignments(experiment.getId(), null, "  AlI ", 2);
            verify(assignments).searchByUsername(experiment, "%ali%", PageRequest.of(2, 20));

            controller.assignments(experiment.getId(), treatment.getId(), "ali", 0);
            verify(assignments).searchByVariantAndUsername(experiment, treatment.getId(), "%ali%", PageRequest.of(0, 20));
        }

        @Test
        void list_escapesLikeWildcardsInTheSearch() {
            controller.assignments(experiment.getId(), null, "a_b%c\\d", 0);

            verify(assignments).searchByUsername(experiment, "%a\\_b\\%c\\\\d%", PageRequest.of(0, 20));
        }

        @Test
        void list_aBlankSearchDoesNotFilterOnTheUsername() {
            controller.assignments(experiment.getId(), null, "   ", 0);

            verify(assignments).searchAll(experiment, PageRequest.of(0, 20));
            verify(assignments, never()).searchByUsername(any(), any(), any());
        }

        @Test
        void list_aVariantOfAnotherExperimentIsNotFound() {
            ExperimentVariant foreign = variant("x", false);
            Experiment other = new Experiment();
            other.setId(UUID.randomUUID());
            foreign.setExperiment(other);
            when(variants.findById(foreign.getId())).thenReturn(Optional.of(foreign));

            assertStatus(() -> controller.assignments(experiment.getId(), foreign.getId(), null, 0), HttpStatus.NOT_FOUND);
            assertStatus(() -> controller.assignments(experiment.getId(), UUID.randomUUID(), null, 0), HttpStatus.NOT_FOUND);
            assertStatus(() -> controller.assignments(UUID.randomUUID(), null, null, 0), HttpStatus.NOT_FOUND);
        }

        @Test
        void list_countsIncludeEmptyVariants() {
            when(assignments.countByVariant(experiment)).thenReturn(java.util.Collections.singletonList(
                    new Object[] {control.getId(), 5L}));
            stubSearch(null, List.of());

            ExperimentAssignmentsDto result = controller.assignments(experiment.getId(), null, null, 0);

            assertThat(result.counts()).containsEntry(control.getId(), 5L).containsEntry(treatment.getId(), 0L);
        }

        @Test
        void list_flagsTheMembersAForcedVariantTargets() {
            UserAccount forced = member("forced");
            UserAccount excluded = member("excluded");
            UserAccount plain = member("plain");
            when(overrides.findByExperimentOrderByPriorityAsc(experiment)).thenReturn(List.of(
                    userOverride(forced, ExperimentOverride.OverrideAction.FORCE_VARIANT, treatment),
                    userOverride(excluded, ExperimentOverride.OverrideAction.FORCE_EXCLUDE, null)));
            stubSearch(null, List.of(assignment(forced, treatment), assignment(excluded, control),
                    assignment(plain, control)));

            ExperimentAssignmentsDto result = controller.assignments(experiment.getId(), null, null, 0);

            assertThat(result.page().content()).extracting(ExperimentAssignmentDto::forced)
                    .containsExactly(true, false, false);
        }

        @Nested
        class Move {
            private UserAccount user;

            @BeforeEach
            void moveSetUp() {
                user = member("alice");
                treatment.setExperiment(experiment);
                when(users.findById(user.getId())).thenReturn(Optional.of(user));
                when(variants.findById(treatment.getId())).thenReturn(Optional.of(treatment));
            }

            private void move(UUID variantId) {
                controller.moveAssignment(experiment.getId(), user.getId(), new MoveAssignmentRequest(variantId));
            }

            @Test
            void createsAForceVariantOverrideAndMovesTheAssignment() {
                ExperimentAssignment current = assignment(user, control);
                when(assignments.findByExperimentAndUser(experiment, user)).thenReturn(Optional.of(current));

                move(treatment.getId());

                ArgumentCaptor<ExperimentOverride> captor = ArgumentCaptor.forClass(ExperimentOverride.class);
                verify(overrides).save(captor.capture());
                ExperimentOverride o = captor.getValue();
                assertThat(o.getOverrideType()).isEqualTo(ExperimentOverride.OverrideType.USER);
                assertThat(o.getAction()).isEqualTo(ExperimentOverride.OverrideAction.FORCE_VARIANT);
                assertThat(o.getTargetUser()).isSameAs(user);
                assertThat(o.getTargetVariant()).isSameAs(treatment);
                assertThat(o.getExperiment()).isSameAs(experiment);
                assertThat(o.getPriority()).isZero();
                assertThat(current.getVariant()).isSameAs(treatment);
                verify(assignments).save(current);
            }

            @Test
            void createsTheAssignmentOfAMemberWithoutOne() {
                when(assignments.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

                move(treatment.getId());

                ArgumentCaptor<ExperimentAssignment> captor = ArgumentCaptor.forClass(ExperimentAssignment.class);
                verify(assignments).save(captor.capture());
                assertThat(captor.getValue().getUser()).isSameAs(user);
                assertThat(captor.getValue().getVariant()).isSameAs(treatment);
                assertThat(captor.getValue().getExperiment()).isSameAs(experiment);
            }

            @Test
            void replacesTheMembersExistingUserOverride() {
                ExperimentOverride exclude = userOverride(user, ExperimentOverride.OverrideAction.FORCE_EXCLUDE, null);
                ExperimentOverride earlier = userOverride(user, ExperimentOverride.OverrideAction.FORCE_VARIANT, control);
                ExperimentOverride someoneElses = userOverride(member("bob"), ExperimentOverride.OverrideAction.FORCE_EXCLUDE, null);
                when(overrides.findByExperimentOrderByPriorityAsc(experiment)).thenReturn(List.of(exclude, earlier, someoneElses));
                when(assignments.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

                move(treatment.getId());

                assertThat(earlier.getTargetVariant()).isSameAs(treatment);
                assertThat(earlier.getPriority()).isZero();
                verify(overrides).save(earlier);
                verify(overrides).delete(exclude);
                verify(overrides, never()).delete(someoneElses);
                verify(overrides, never()).save(someoneElses);
            }

            @Test
            void anIncludeOrExcludeOverrideBecomesTheForceVariantOne() {
                ExperimentOverride exclude = userOverride(user, ExperimentOverride.OverrideAction.FORCE_EXCLUDE, null);
                when(overrides.findByExperimentOrderByPriorityAsc(experiment)).thenReturn(List.of(exclude));
                when(assignments.findByExperimentAndUser(experiment, user)).thenReturn(Optional.empty());

                move(treatment.getId());

                assertThat(exclude.getAction()).isEqualTo(ExperimentOverride.OverrideAction.FORCE_VARIANT);
                assertThat(exclude.getTargetVariant()).isSameAs(treatment);
                verify(overrides).save(exclude);
            }

            @Test
            void anEndedExperimentIsAConflict() {
                experiment.setStatus(Experiment.Status.ENDED);

                assertStatus(() -> move(treatment.getId()), HttpStatus.CONFLICT);
                verify(overrides, never()).save(any());
            }

            @Test
            void aVariantOfAnotherExperimentIsNotFound() {
                ExperimentVariant foreign = variant("x", false);
                Experiment other = new Experiment();
                other.setId(UUID.randomUUID());
                foreign.setExperiment(other);
                when(variants.findById(foreign.getId())).thenReturn(Optional.of(foreign));

                assertStatus(() -> move(foreign.getId()), HttpStatus.NOT_FOUND);
                assertStatus(() -> move(UUID.randomUUID()), HttpStatus.NOT_FOUND);
                verify(overrides, never()).save(any());
            }

            @Test
            void anUnknownUserIsNotFound() {
                assertStatus(() -> controller.moveAssignment(experiment.getId(), UUID.randomUUID(),
                        new MoveAssignmentRequest(treatment.getId())), HttpStatus.NOT_FOUND);
            }

            @Test
            void anUnknownExperimentIsNotFound() {
                assertStatus(() -> controller.moveAssignment(UUID.randomUUID(), user.getId(),
                        new MoveAssignmentRequest(treatment.getId())), HttpStatus.NOT_FOUND);
            }

            @Test
            void aMissingVariantIsABadRequest() {
                assertStatus(() -> move(null), HttpStatus.BAD_REQUEST);
                assertStatus(() -> controller.moveAssignment(experiment.getId(), user.getId(), null), HttpStatus.BAD_REQUEST);
            }
        }
    }
}
