package fr.enimaloc.catapult.service.feedback;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.feedback.FeedbackSubmission;
import fr.enimaloc.catapult.repository.feedback.FeedbackSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What users send as feedback: a GitLab issue, remembered so its progress can be followed. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackSubmissionRepository repository;
    private final GitLabClient gitLabClient;

    /** The member's submissions, newest first, without the (lazy) member: mapped inside the transaction. */
    @Transactional(readOnly = true)
    public List<Submission> list(UserAccount user) {
        return repository.findByUserOrderByCreatedAtDesc(user).stream().map(Submission::of).toList();
    }

    /** Opens the GitLab issue first, then saves the submission. */
    public FeedbackSubmission submit(UserAccount user, String title, FeedbackSubmission.Type feedbackType, String description) {
        String label = feedbackType == FeedbackSubmission.Type.BUG ? "bug" : "enhancement";
        String issueBody = buildIssueBody(user, description);

        GitLabClient.CreatedIssue created = gitLabClient.createIssue(title.strip(), issueBody, List.of(label, "user-submitted"));

        FeedbackSubmission submission = new FeedbackSubmission();
        submission.setUser(user);
        submission.setType(feedbackType);
        submission.setTitle(title.strip());
        submission.setDescription(strippedOrNull(description));
        submission.setGitlabIssueIid(created.iid());
        submission.setGitlabIssueUrl(created.webUrl());
        submission.setLastKnownUpdatedAt(created.updatedAt());
        repository.save(submission);

        log.info("Feedback submitted by user {}: {} #{}", user.getId(), feedbackType, created.iid());
        return submission;
    }

    public void unsubscribe(UserAccount user, UUID id) {
        FeedbackSubmission submission = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!submission.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        submission.setSubscribed(false);
        repository.save(submission);
    }

    /** The description (if any) followed by a signature naming the submitter. */
    private static String buildIssueBody(UserAccount user, String description) {
        String text = strippedOrNull(description);
        String body = text != null ? text + "\n\n" : "";
        return body + "---\n*Soumis par @" + user.getTwitchUsername() + " via Catapult*";
    }

    private static String strippedOrNull(String text) {
        return text != null && !text.isBlank() ? text.strip() : null;
    }

    /** A submission as its member lists it: every column but the member. */
    public record Submission(UUID id, FeedbackSubmission.Type type, String title, String description,
                             int gitlabIssueIid, String gitlabIssueUrl, Instant lastKnownUpdatedAt,
                             boolean subscribed, Instant createdAt) {

        static Submission of(FeedbackSubmission submission) {
            return new Submission(submission.getId(), submission.getType(), submission.getTitle(),
                    submission.getDescription(), submission.getGitlabIssueIid(), submission.getGitlabIssueUrl(),
                    submission.getLastKnownUpdatedAt(), submission.isSubscribed(), submission.getCreatedAt());
        }
    }
}
