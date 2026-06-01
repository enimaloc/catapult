package fr.enimaloc.catapult.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(value = "gitlab.token", havingValue = "")
public class GitLabClient {

    @Value("${gitlab.base-url:https://git.enimaloc.fr}")
    private String baseUrl;

    @Value("${gitlab.token:}")
    private String token;

    @Value("${gitlab.project-path:1}")
    private String projectId;

    private final RestClient restClient;

    public GitLabClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public record CreatedIssue(int iid, String webUrl, Instant updatedAt) {}

    public record IssueState(int iid, String state, Instant updatedAt) {}

    @SuppressWarnings("unchecked")
    public CreatedIssue createIssue(String title, String description, List<String> labels) {
        String labelsStr = String.join(",", labels);

        Map<String, Object> response = restClient.post()
            .uri(baseUrl + "/api/v4/projects/" + projectId + "/issues")
            .header("PRIVATE-TOKEN", token)
            .body(Map.of("title", title, "description", description, "labels", labelsStr))
            .retrieve()
            .body(Map.class);

        if (response == null) throw new IllegalStateException("Empty response from GitLab");

        int iid = ((Number) response.get("iid")).intValue();
        String webUrl = (String) response.get("web_url");
        Instant updatedAt = Instant.parse((String) response.get("updated_at"));
        return new CreatedIssue(iid, webUrl, updatedAt);
    }

    @SuppressWarnings("unchecked")
    public IssueState getIssue(int iid) {
        Map<String, Object> response = restClient.get()
            .uri(baseUrl + "/api/v4/projects/" + projectId + "/issues/" + iid)
            .header("PRIVATE-TOKEN", token)
            .retrieve()
            .body(Map.class);

        if (response == null) throw new IllegalStateException("Empty response from GitLab");

        String state = (String) response.get("state");
        Instant updatedAt = Instant.parse((String) response.get("updated_at"));
        return new IssueState(iid, state, updatedAt);
    }
}
