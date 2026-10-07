package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.SubmitRequest;
import fr.enimaloc.catapult.domain.FeedbackSubmission;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.service.GitLabClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** User feedback: turned into GitLab issues, listed back, and unsubscribed from. */
class ApiFeedbackControllerTest {

    private final FeedbackSubmissionRepository repository = mock(FeedbackSubmissionRepository.class);
    private final ApiUserResolver users = mock(ApiUserResolver.class);
    private final GitLabClient gitLab = mock(GitLabClient.class);
    private final ApiFeedbackController controller = new ApiFeedbackController(repository, users, gitLab);
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s").build();
    private UserAccount viewer;

    @BeforeEach
    void setUp() {
        viewer = new UserAccount();
        viewer.setId(UUID.randomUUID());
        viewer.setTwitchUsername("streamer");
        when(users.viewer(jwt)).thenReturn(viewer);
        when(gitLab.createIssue(anyString(), anyString(), anyList()))
                .thenReturn(new GitLabClient.CreatedIssue(42, "https://gitlab/issues/42", Instant.EPOCH));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void bugs_becomeLabelledIssues_signedByTheSubmitter() {
        FeedbackSubmission submission = controller.submit(jwt, new SubmitRequest("bug", "  Crash  ", " It crashed. "));

        verify(gitLab).createIssue("Crash", "It crashed.\n\n---\n*Soumis par @streamer via Catapult*", List.of("bug", "user-submitted"));
        assertThat(submission.getTitle()).isEqualTo("Crash");
        assertThat(submission.getDescription()).isEqualTo("It crashed.");
        assertThat(submission.getType()).isEqualTo(FeedbackSubmission.Type.BUG);
        assertThat(submission.getGitlabIssueIid()).isEqualTo(42);
        assertThat(submission.getUser()).isSameAs(viewer);
        verify(repository).save(submission);
    }

    @Test
    void featureRequests_withoutDescription() {
        FeedbackSubmission submission = controller.submit(jwt, new SubmitRequest("Feature", "Dark mode", " "));

        verify(gitLab).createIssue("Dark mode", "---\n*Soumis par @streamer via Catapult*", List.of("enhancement", "user-submitted"));
        assertThat(submission.getDescription()).isNull();
    }

    @Test
    void submissions_needATitleAndAKnownType() {
        assertStatus(() -> controller.submit(jwt, new SubmitRequest("bug", " ", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.submit(jwt, new SubmitRequest("bug", null, null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.submit(jwt, new SubmitRequest("rant", "Title", null)), HttpStatus.BAD_REQUEST);
        verify(gitLab, never()).createIssue(anyString(), anyString(), anyList());
    }

    @Test
    void list_returnsTheViewersOwnSubmissions() {
        FeedbackSubmission mine = new FeedbackSubmission();
        when(repository.findByUserOrderByCreatedAtDesc(viewer)).thenReturn(List.of(mine));

        assertThat(controller.list(jwt)).containsExactly(mine);
    }

    @Test
    void unsubscribe_onlyFromOnesOwnSubmissions() {
        FeedbackSubmission mine = new FeedbackSubmission();
        mine.setUser(viewer);
        mine.setSubscribed(true);
        UUID mineId = UUID.randomUUID();
        UserAccount someoneElse = new UserAccount();
        someoneElse.setId(UUID.randomUUID());
        FeedbackSubmission theirs = new FeedbackSubmission();
        theirs.setUser(someoneElse);
        UUID theirsId = UUID.randomUUID();
        when(repository.findById(any(UUID.class))).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return id.equals(mineId) ? Optional.of(mine) : id.equals(theirsId) ? Optional.of(theirs) : Optional.empty();
        });

        controller.unsubscribe(jwt, mineId);

        assertThat(mine.isSubscribed()).isFalse();
        verify(repository).save(mine);
        assertStatus(() -> controller.unsubscribe(jwt, theirsId), HttpStatus.FORBIDDEN);
        assertStatus(() -> controller.unsubscribe(jwt, UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }
}
