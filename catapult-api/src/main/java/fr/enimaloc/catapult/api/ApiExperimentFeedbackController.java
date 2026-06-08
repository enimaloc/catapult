package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.ExperimentFeedback;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.ExperimentFeedbackRepository;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/experiments")
@RequiredArgsConstructor
public class ApiExperimentFeedbackController {

    private final ExperimentRepository experimentRepository;
    private final ExperimentAssignmentRepository assignmentRepository;
    private final ExperimentFeedbackRepository feedbackRepository;
    private final UserAccountRepository userAccountRepository;

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@AuthenticationPrincipal Jwt jwt, @RequestBody FeedbackRequest body) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

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

    public record FeedbackRequest(UUID experimentId, int npsScore, String comment) {}
}
