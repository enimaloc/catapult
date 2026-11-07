package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.feedback.GitLabClient;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitLab's feedback issues, in the global {@code gitlab.feedback} mode. {@code REAL} hands the call to the real
 * {@link GitLabClient} bean. Otherwise an issue gets a made-up number and stays open, never newer than what the
 * feedback poller already knows (so it notifies nothing); a failure throws what the {@code RestClient} would, as the
 * real client lets it through. A subclass so that no caller changes: the superclass's state is never used.
 */
@Primary
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedGitLabClient extends GitLabClient {

    private final ObjectProvider<GitLabClient> real;
    private final SimulatedCallRunner runner;
    private final String baseUrl;
    private final AtomicInteger nextIid = new AtomicInteger(9000);

    public SimulatedGitLabClient(@Qualifier("gitLabClient") ObjectProvider<GitLabClient> real,
                                 SimulatedCallRunner runner,
                                 @Value("${gitlab.base-url:https://git.enimaloc.fr}") String baseUrl) {
        super(null, null);
        this.real = real;
        this.runner = runner;
        this.baseUrl = baseUrl;
    }

    @Override
    public CreatedIssue createIssue(String title, String description, List<String> labels) {
        return runner.run(null, SimulatedCalls.GITLAB_FEEDBACK, "POST issues '" + title + "'",
                () -> real().createIssue(title, description, labels), () -> {
                    int iid = nextIid.incrementAndGet();
                    return new CreatedIssue(iid, baseUrl + "/simulated/-/issues/" + iid, Instant.now());
                }, SimulatedCallRunner::httpError);
    }

    @Override
    public IssueState getIssue(int iid) {
        return runner.run(null, SimulatedCalls.GITLAB_FEEDBACK, "GET issues/" + iid,
                () -> real().getIssue(iid), () -> new IssueState(iid, "opened", Instant.EPOCH),
                SimulatedCallRunner::httpError);
    }

    private GitLabClient real() {
        GitLabClient client = real.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("The real GitLab client is not available");
        }
        return client;
    }
}
