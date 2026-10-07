package fr.enimaloc.catapult.api.experiment;

import fr.enimaloc.catapult.api.ApiUserResolver;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;


@RestController
@RequestMapping("/api/experiments")
@RequiredArgsConstructor
public class ApiExperimentFeedbackController {

    private final ExperimentRepository experimentRepository;
    private final ExperimentAssignmentRepository assignmentRepository;
    private final ExperimentFeedbackRepository feedbackRepository;
    private final ApiUserResolver userResolver;

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@AuthenticationPrincipal Jwt jwt, @RequestBody FeedbackRequest body) {
        UserAccount user = userResolver.viewer(jwt);

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
