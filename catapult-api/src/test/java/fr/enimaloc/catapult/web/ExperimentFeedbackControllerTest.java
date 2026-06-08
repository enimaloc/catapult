package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentFeedbackControllerTest {

    @Mock ExperimentRepository experimentRepository;
    @Mock ExperimentAssignmentRepository assignmentRepository;
    @Mock ExperimentFeedbackRepository feedbackRepository;
    @InjectMocks ExperimentFeedbackController controller;

    private UserAccount user() {
        UserAccount u = new UserAccount();
        u.setId(UUID.randomUUID());
        return u;
    }

    @Test
    void submit_savesFeedbackAndReturnsConfirmationFragment() {
        UserAccount user = user();
        Experiment exp = new Experiment();
        exp.setId(UUID.randomUUID());
        exp.setStatus(Experiment.Status.ACTIVE);
        ExperimentVariant variant = new ExperimentVariant();
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setVariant(variant);
        assignment.setExperiment(exp);

        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.of(assignment));
        when(feedbackRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.empty());

        CatapultOAuth2User principal = mock(CatapultOAuth2User.class);
        when(principal.getUserAccount()).thenReturn(user);

        String view = controller.submit(principal, exp.getId(), 8, "Nice feature");

        ArgumentCaptor<ExperimentFeedback> captor = ArgumentCaptor.forClass(ExperimentFeedback.class);
        verify(feedbackRepository).save(captor.capture());
        assertThat(captor.getValue().getNpsScore()).isEqualTo(8);
        assertThat(captor.getValue().getComment()).isEqualTo("Nice feature");
        assertThat(view).isEqualTo("fragments/experiment-feedback-widget :: confirmed");
    }

    @Test
    void submit_returns404_whenUserNotAssigned() {
        UserAccount user = user();
        Experiment exp = new Experiment();
        exp.setId(UUID.randomUUID());
        exp.setStatus(Experiment.Status.ACTIVE);

        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.empty());

        CatapultOAuth2User principal = mock(CatapultOAuth2User.class);
        when(principal.getUserAccount()).thenReturn(user);

        org.springframework.web.server.ResponseStatusException ex = assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> controller.submit(principal, exp.getId(), 7, null)
        );
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void submit_returns404_whenExperimentNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(experimentRepository.findById(unknownId)).thenReturn(Optional.empty());
        CatapultOAuth2User principal = mock(CatapultOAuth2User.class);
        when(principal.getUserAccount()).thenReturn(user());
        org.springframework.web.server.ResponseStatusException ex = assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> controller.submit(principal, unknownId, 8, null));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void submit_returns403_whenExperimentNotActive() {
        UserAccount user = user();
        Experiment exp = new Experiment();
        exp.setId(UUID.randomUUID());
        // default status is DRAFT — not ACTIVE
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        CatapultOAuth2User principal = mock(CatapultOAuth2User.class);
        when(principal.getUserAccount()).thenReturn(user);
        org.springframework.web.server.ResponseStatusException ex = assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> controller.submit(principal, exp.getId(), 8, null));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 11})
    void submit_returns400_whenNpsScoreOutOfRange(int score) {
        UserAccount user = user();
        Experiment exp = new Experiment();
        exp.setId(UUID.randomUUID());
        exp.setStatus(Experiment.Status.ACTIVE);
        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        CatapultOAuth2User principal = mock(CatapultOAuth2User.class);
        when(principal.getUserAccount()).thenReturn(user);
        org.springframework.web.server.ResponseStatusException ex = assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> controller.submit(principal, exp.getId(), score, null));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void submit_updatesExistingFeedback_whenAlreadySubmitted() {
        UserAccount user = user();
        Experiment exp = new Experiment();
        exp.setId(UUID.randomUUID());
        exp.setStatus(Experiment.Status.ACTIVE);
        ExperimentVariant variant = new ExperimentVariant();
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setVariant(variant);
        assignment.setExperiment(exp);
        ExperimentFeedback existingFeedback = new ExperimentFeedback();
        existingFeedback.setNpsScore(5);

        when(experimentRepository.findById(exp.getId())).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.of(assignment));
        when(feedbackRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.of(existingFeedback));

        CatapultOAuth2User principal = mock(CatapultOAuth2User.class);
        when(principal.getUserAccount()).thenReturn(user);

        controller.submit(principal, exp.getId(), 9, "Updated");

        ArgumentCaptor<ExperimentFeedback> captor = ArgumentCaptor.forClass(ExperimentFeedback.class);
        verify(feedbackRepository).save(captor.capture());
        assertThat(captor.getValue().getNpsScore()).isEqualTo(9);
        assertThat(captor.getValue()).isSameAs(existingFeedback); // same instance, updated
    }
}
