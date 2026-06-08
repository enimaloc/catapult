package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.FeedbackSubmission;
import fr.enimaloc.catapult.repository.FeedbackSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackNotificationService {

    private final FeedbackSubmissionRepository repository;
    private final GitLabClient gitLabClient;
    private final ActivityLogService activityLogService;

    @Scheduled(fixedDelayString = "${app.feedback.poll-interval-ms:300000}")
    @Transactional
    public void pollUpdates() {
        List<FeedbackSubmission> subscribed = repository.findBySubscribedTrue();
        for (FeedbackSubmission submission : subscribed) {
            try {
                GitLabClient.IssueState state = gitLabClient.getIssue(submission.getGitlabIssueIid());

                if (state.updatedAt().isAfter(submission.getLastKnownUpdatedAt())) {
                    String message = buildNotificationMessage(submission, state);
                    activityLogService.addEntry(submission.getUser().getId(), "INFO", message);
                    submission.setLastKnownUpdatedAt(state.updatedAt());
                    repository.save(submission);
                }

                if ("closed".equals(state.state())) {
                    submission.setSubscribed(false);
                    repository.save(submission);
                }
            } catch (Exception e) {
                log.warn("Failed to poll GitLab issue #{} for user {}: {}",
                    submission.getGitlabIssueIid(), submission.getUser().getId(), e.getMessage());
            }
        }
    }

    private String buildNotificationMessage(FeedbackSubmission submission, GitLabClient.IssueState state) {
        String type = submission.getType() == FeedbackSubmission.Type.BUG ? "Bug" : "Feature";
        String status = "closed".equals(state.state()) ? "fermée" : "mise à jour";
        return type + " #" + submission.getGitlabIssueIid()
            + " « " + submission.getTitle() + " » " + status
            + " → " + submission.getGitlabIssueUrl();
    }
}
