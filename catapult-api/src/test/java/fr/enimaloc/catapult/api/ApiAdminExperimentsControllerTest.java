package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.AssignRequest;
import fr.enimaloc.catapult.common.dto.NpsStat;
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
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
        control = variant("old", true);
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
    void list_returnsEveryExperiment() {
        when(experiments.findAll()).thenReturn(List.of(experiment));

        assertThat(controller.list()).containsExactly(experiment);
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
}
