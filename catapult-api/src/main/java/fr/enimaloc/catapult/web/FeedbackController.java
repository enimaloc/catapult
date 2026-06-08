package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.FeedbackSubmission;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.GitLabClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackSubmissionRepository repository;
    private final GitLabClient gitLabClient;

    @GetMapping
    public String page(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal.getUserAccount();
        List<FeedbackSubmission> submissions = repository.findByUserOrderByCreatedAtDesc(user);
        model.addAttribute("submissions", submissions);
        return "feedback";
    }

    @PostMapping
    public String submit(
        @AuthenticationPrincipal CatapultOAuth2User principal,
        @RequestParam String type,
        @RequestParam String title,
        @RequestParam(required = false) String description
    ) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required");
        }

        FeedbackSubmission.Type feedbackType;
        try {
            feedbackType = FeedbackSubmission.Type.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type");
        }

        UserAccount user = principal.getUserAccount();
        String label = feedbackType == FeedbackSubmission.Type.BUG ? "bug" : "enhancement";
        String body = buildIssueBody(user, description);

        GitLabClient.CreatedIssue created = gitLabClient.createIssue(title.strip(), body, List.of(label, "user-submitted"));

        FeedbackSubmission submission = new FeedbackSubmission();
        submission.setUser(user);
        submission.setType(feedbackType);
        submission.setTitle(title.strip());
        submission.setDescription(description != null && !description.isBlank() ? description.strip() : null);
        submission.setGitlabIssueIid(created.iid());
        submission.setGitlabIssueUrl(created.webUrl());
        submission.setLastKnownUpdatedAt(created.updatedAt());
        repository.save(submission);

        log.info("Feedback submitted by user {}: {} #{}", user.getId(), feedbackType, created.iid());
        return "fragments/feedback-widget :: submitted";
    }

    @PostMapping("/{id}/unsubscribe")
    public String unsubscribe(
        @AuthenticationPrincipal CatapultOAuth2User principal,
        @PathVariable UUID id
    ) {
        FeedbackSubmission submission = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!submission.getUser().getId().equals(principal.getUserAccount().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        submission.setSubscribed(false);
        repository.save(submission);
        return "fragments/feedback-widget :: unsubscribed(submission=${submission})";
    }

    private String buildIssueBody(UserAccount user, String description) {
        String body = description != null && !description.isBlank() ? description.strip() + "\n\n" : "";
        return body + "---\n*Soumis par @" + user.getTwitchUsername() + " via Catapult*";
    }
}
