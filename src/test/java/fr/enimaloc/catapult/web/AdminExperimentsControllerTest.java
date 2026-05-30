package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminExperimentsControllerTest {

    @Mock ExperimentRepository experimentRepository;
    @Mock ExperimentEventRepository eventRepository;
    @Mock ExperimentFeedbackRepository feedbackRepository;
    @Mock ExperimentAssignmentRepository assignmentRepository;
    @Mock UserAccountRepository userAccountRepository;
    @Mock StatisticsService statisticsService;
    @Mock ExperimentOverrideRepository overrideRepository;
    @Mock ExperimentAssignmentRuleRepository ruleRepository;
    @Mock ExperimentVariantRepository variantRepository;
    @Mock ExperimentService experimentService;
    @Mock CatapultOAuth2User principal;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks AdminExperimentsController controller;

    @BeforeEach
    void setUp() {
        when(principal.getUserAccount()).thenReturn(new UserAccount());
    }

    @Test
    void list_addsAllExperimentsToModel() {
        Experiment exp = new Experiment();
        exp.setKey("test");
        when(experimentRepository.findAll()).thenReturn(List.of(exp));

        Model model = new ExtendedModelMap();
        String view = controller.list(principal, model);

        assertThat(view).isEqualTo("admin/experiments");
        @SuppressWarnings("unchecked")
        List<Experiment> experiments = (List<Experiment>) model.getAttribute("experiments");
        assertThat(experiments).containsExactly(exp);
    }

    @Test
    void detail_addsExperimentAndStatsToModel() {
        Experiment exp = new Experiment();
        UUID id = UUID.randomUUID();
        exp.setId(id);
        exp.setVariants(List.of());
        exp.setRules(List.of());
        when(experimentRepository.findById(id)).thenReturn(Optional.of(exp));
        when(eventRepository.findDistinctEventKeysByExperiment(exp)).thenReturn(List.of());
        when(feedbackRepository.findByExperiment(eq(exp), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(assignmentRepository.findByExperiment(eq(exp), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        Model model = new ExtendedModelMap();
        String view = controller.detail(principal, id, model);

        assertThat(view).isEqualTo("admin/experiment-detail");
        assertThat(model.getAttribute("experiment")).isEqualTo(exp);
    }

    @Test
    void activate_changesStatusAndRedirects() {
        Experiment exp = new Experiment();
        UUID id = UUID.randomUUID();
        exp.setId(id);
        exp.setStatus(Experiment.Status.DRAFT);
        when(experimentRepository.findById(id)).thenReturn(Optional.of(exp));

        String view = controller.activate(id);

        assertThat(exp.getStatus()).isEqualTo(Experiment.Status.ACTIVE);
        verify(experimentRepository).save(exp);
        assertThat(view).isEqualTo("redirect:/admin/experiments/" + id);
    }

    @Test
    void pause_changesStatusAndRedirects() {
        Experiment exp = new Experiment();
        UUID id = UUID.randomUUID();
        exp.setId(id);
        exp.setStatus(Experiment.Status.ACTIVE);
        when(experimentRepository.findById(id)).thenReturn(Optional.of(exp));

        String view = controller.pause(id);

        assertThat(exp.getStatus()).isEqualTo(Experiment.Status.PAUSED);
        verify(experimentRepository).save(exp);
        assertThat(view).isEqualTo("redirect:/admin/experiments/" + id);
    }

    @Test
    void end_changesStatusAndSetsEndedAt_andRedirects() {
        Experiment exp = new Experiment();
        UUID id = UUID.randomUUID();
        exp.setId(id);
        exp.setStatus(Experiment.Status.ACTIVE);
        when(experimentRepository.findById(id)).thenReturn(Optional.of(exp));

        String view = controller.end(id);

        assertThat(exp.getStatus()).isEqualTo(Experiment.Status.ENDED);
        assertThat(exp.getEndedAt()).isNotNull();
        verify(experimentRepository).save(exp);
        assertThat(view).isEqualTo("redirect:/admin/experiments/" + id);
    }

    @Test
    void detail_returns404_whenExperimentNotFound() {
        UUID id = UUID.randomUUID();
        when(experimentRepository.findById(id)).thenReturn(Optional.empty());

        org.springframework.web.server.ResponseStatusException ex =
            org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.detail(principal, id, new ExtendedModelMap())
            );
        assertThat(ex.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void assignUser_savesAssignment_whenUserFoundAndNotYetAssigned() {
        Experiment exp = experiment();
        ExperimentVariant control = new ExperimentVariant();
        control.setKey("control");
        control.setControl(true);
        exp.getVariants().add(control);
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        UserAccount user = new UserAccount();
        when(userAccountRepository.findByTwitchUsername("streamer1")).thenReturn(Optional.of(user));
        when(assignmentRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.empty());

        String result = controller.assignUser(exp.getId(), "streamer1");

        verify(assignmentRepository).save(any(ExperimentAssignment.class));
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    @Test
    void assignUser_redirectsWithError_whenUserNotFound() {
        Experiment exp = experiment();
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        when(userAccountRepository.findByTwitchUsername("unknown")).thenReturn(Optional.empty());

        String result = controller.assignUser(exp.getId(), "unknown");

        verify(assignmentRepository, never()).save(any());
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId() + "?assignError=userNotFound");
    }

    @Test
    void assignUser_redirectsWithError_whenExperimentNotActive() {
        Experiment exp = experiment();
        exp.setStatus(Experiment.Status.DRAFT);
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));

        String result = controller.assignUser(exp.getId(), "streamer1");

        verify(assignmentRepository, never()).save(any());
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId() + "?assignError=notActive");
    }

    @Test
    void assignUser_redirectsWithError_whenAlreadyAssigned() {
        Experiment exp = experiment();
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        UserAccount user = new UserAccount();
        when(userAccountRepository.findByTwitchUsername("streamer1")).thenReturn(Optional.of(user));
        ExperimentAssignment existing = new ExperimentAssignment();
        when(assignmentRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.of(existing));

        String result = controller.assignUser(exp.getId(), "streamer1");

        verify(assignmentRepository, never()).save(any());
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId() + "?assignError=alreadyAssigned");
    }

    @Test
    void updateWeights_savesEachVariantAndRedirects() {
        Experiment exp = experiment();
        ExperimentVariant v = new ExperimentVariant();
        v.setId(UUID.randomUUID());
        v.setWeight(50);
        exp.getVariants().add(v);
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));

        String result = controller.updateWeights(exp.getId(), Map.of("weight_" + v.getId(), "70"));

        verify(variantRepository).save(v);
        assertThat(v.getWeight()).isEqualTo(70);
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    @Test
    void addRule_savesRuleAndRedirects() {
        Experiment exp = experiment();
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));

        String result = controller.addRule(exp.getId(),
            ExperimentAssignmentRule.RuleType.RANDOM, 0, 80, null, null, null);

        ArgumentCaptor<ExperimentAssignmentRule> captor =
            ArgumentCaptor.forClass(ExperimentAssignmentRule.class);
        verify(ruleRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleType()).isEqualTo(ExperimentAssignmentRule.RuleType.RANDOM);
        assertThat(captor.getValue().getPercentage()).isEqualTo(80);
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    @Test
    void deleteRule_deletesAndRedirects() {
        Experiment exp = experiment();
        UUID ruleId = UUID.randomUUID();
        ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
        rule.setExperiment(exp);
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        String result = controller.deleteRule(exp.getId(), ruleId);

        verify(ruleRepository).delete(rule);
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    @Test
    void addOverride_savesUserOverrideAndRedirects() {
        Experiment exp = experiment();
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userAccountRepository.findByTwitchUsername("streamer1")).thenReturn(Optional.of(user));

        String result = controller.addOverride(exp.getId(),
            ExperimentOverride.OverrideType.USER,
            ExperimentOverride.OverrideAction.FORCE_EXCLUDE,
            0, "streamer1", null, null, null, null);

        ArgumentCaptor<ExperimentOverride> captor =
            ArgumentCaptor.forClass(ExperimentOverride.class);
        verify(overrideRepository).save(captor.capture());
        assertThat(captor.getValue().getTargetUser()).isEqualTo(user);
        assertThat(captor.getValue().getAction()).isEqualTo(ExperimentOverride.OverrideAction.FORCE_EXCLUDE);
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    @Test
    void addOverride_savesAttributeOverrideAndRedirects() {
        Experiment exp = experiment();
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));

        String result = controller.addOverride(exp.getId(),
            ExperimentOverride.OverrideType.ATTRIBUTE,
            ExperimentOverride.OverrideAction.FORCE_INCLUDE,
            0, null, "has_steam", "eq", "1.0", null);

        ArgumentCaptor<ExperimentOverride> captor =
            ArgumentCaptor.forClass(ExperimentOverride.class);
        verify(overrideRepository).save(captor.capture());
        assertThat(captor.getValue().getOverrideType()).isEqualTo(ExperimentOverride.OverrideType.ATTRIBUTE);
        assertThat(captor.getValue().getAttributeKey()).isEqualTo("has_steam");
        assertThat(captor.getValue().getAttributeOp()).isEqualTo("eq");
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    @Test
    void deleteOverride_deletesAndRedirects() {
        Experiment exp = experiment();
        UUID overrideId = UUID.randomUUID();
        ExperimentOverride override = new ExperimentOverride();
        override.setExperiment(exp);
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        when(overrideRepository.findById(overrideId)).thenReturn(Optional.of(override));

        String result = controller.deleteOverride(exp.getId(), overrideId);

        verify(overrideRepository).delete(override);
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    @Test
    void reassign_callsReassignAllAndRedirects() {
        Experiment exp = experiment();
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));

        String result = controller.reassign(exp.getId());

        verify(experimentService).reassignAll(exp);
        assertThat(result).isEqualTo("redirect:/admin/experiments/" + exp.getId());
    }

    private Experiment experiment() {
        Experiment exp = new Experiment();
        exp.setId(UUID.randomUUID());
        exp.setStatus(Experiment.Status.ACTIVE);
        exp.setVariants(new ArrayList<>());
        exp.setRules(List.of());
        return exp;
    }
}
