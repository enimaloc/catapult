package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.FeedbackSubmission;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.GitLabClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class ApiFeedbackController {

    private final FeedbackSubmissionRepository repository;
    private final UserAccountRepository userAccountRepository;
    private final GitLabClient gitLabClient;

    @GetMapping
    public List<FeedbackSubmission> list(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = resolveUser(jwt);
        return repository.findByUserOrderByCreatedAtDesc(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackSubmission submit(@AuthenticationPrincipal Jwt jwt, @RequestBody SubmitRequest body) {
        if (body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required");
        }

        FeedbackSubmission.Type feedbackType;
        try {
            feedbackType = FeedbackSubmission.Type.valueOf(body.type().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type");
        }

        UserAccount user = resolveUser(jwt);
        String label = feedbackType == FeedbackSubmission.Type.BUG ? "bug" : "enhancement";
        String issueBody = buildIssueBody(user, body.description());

        GitLabClient.CreatedIssue created = gitLabClient.createIssue(body.title().strip(), issueBody, List.of(label, "user-submitted"));

        FeedbackSubmission submission = new FeedbackSubmission();
        submission.setUser(user);
        submission.setType(feedbackType);
        submission.setTitle(body.title().strip());
        submission.setDescription(body.description() != null && !body.description().isBlank() ? body.description().strip() : null);
        submission.setGitlabIssueIid(created.iid());
        submission.setGitlabIssueUrl(created.webUrl());
        submission.setLastKnownUpdatedAt(created.updatedAt());
        repository.save(submission);

        log.info("Feedback submitted by user {}: {} #{}", user.getId(), feedbackType, created.iid());
        return submission;
    }

    @PostMapping("/{id}/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        FeedbackSubmission submission = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UserAccount user = resolveUser(jwt);
        if (!submission.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        submission.setSubscribed(false);
        repository.save(submission);
    }

    private UserAccount resolveUser(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private String buildIssueBody(UserAccount user, String description) {
        String body = description != null && !description.isBlank() ? description.strip() + "\n\n" : "";
        return body + "---\n*Soumis par @" + user.getTwitchUsername() + " via Catapult*";
    }

    public record SubmitRequest(String type, String title, String description) {}
}
