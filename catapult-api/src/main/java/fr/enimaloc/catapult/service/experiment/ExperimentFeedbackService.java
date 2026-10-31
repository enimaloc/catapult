package fr.enimaloc.catapult.service.experiment;

import fr.enimaloc.catapult.common.dto.experiment.FeedbackRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentFeedback;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentFeedbackRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A participant's NPS feedback on an active experiment they are assigned to, one per member and experiment (a new
 * submission replaces the previous one). Not transactional, as the route it serves was not.
 */
@Service
@RequiredArgsConstructor
public class ExperimentFeedbackService {

    private final ExperimentRepository experimentRepository;
    private final ExperimentAssignmentRepository assignmentRepository;
    private final ExperimentFeedbackRepository feedbackRepository;

    public void submit(UserAccount user, FeedbackRequest body) {
        Experiment experiment = experimentRepository.findById(body.experimentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (experiment.getStatus() != Experiment.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        if (body.npsScore() < 0 || body.npsScore() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "npsScore must be 0-10");
        }

        ExperimentAssignment assignment = assignmentRepository.findByExperimentAndUser(experiment, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        ExperimentFeedback feedback = feedbackRepository.findByExperimentAndUser(experiment, user)
                .orElseGet(ExperimentFeedback::new);
        feedback.setExperiment(experiment);
        feedback.setVariant(assignment.getVariant());
        feedback.setUser(user);
        feedback.setNpsScore(body.npsScore());
        feedback.setComment(body.comment() != null && !body.comment().isBlank() ? body.comment().strip() : null);
        feedbackRepository.save(feedback);
    }
}
