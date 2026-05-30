package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Controller
@RequestMapping("/experiments")
@RequiredArgsConstructor
public class ExperimentFeedbackController {

    private final ExperimentRepository experimentRepository;
    private final ExperimentAssignmentRepository assignmentRepository;
    private final ExperimentFeedbackRepository feedbackRepository;

    @PostMapping("/feedback")
    public String submit(
        @AuthenticationPrincipal CatapultOAuth2User principal,
        @RequestParam UUID experimentId,
        @RequestParam int npsScore,
        @RequestParam(required = false) String comment
    ) {
        UserAccount user = principal.getUserAccount();
        Experiment experiment = experimentRepository.findById(experimentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (experiment.getStatus() != Experiment.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        if (npsScore < 0 || npsScore > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "npsScore must be 0-10");
        }

        ExperimentAssignment assignment = assignmentRepository.findByExperimentAndUser(experiment, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        ExperimentFeedback feedback = feedbackRepository.findByExperimentAndUser(experiment, user)
            .orElseGet(ExperimentFeedback::new);
        feedback.setExperiment(experiment);
        feedback.setVariant(assignment.getVariant());
        feedback.setUser(user);
        feedback.setNpsScore(npsScore);
        feedback.setComment(comment != null && !comment.isBlank() ? comment.strip() : null);
        feedbackRepository.save(feedback);

        return "fragments/experiment-feedback-widget :: confirmed";
    }
}
