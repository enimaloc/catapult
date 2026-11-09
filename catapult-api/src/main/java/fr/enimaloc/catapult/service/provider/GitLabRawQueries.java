package fr.enimaloc.catapult.service.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** The admin probe of the GitLab API: one raw call, answered as the provider sent it. */
@Service
@RequiredArgsConstructor
public class GitLabRawQueries {

    @Value("${gitlab.base-url:https://git.enimaloc.fr}")
    private String baseUrl;

    @Value("${gitlab.token:}")
    private String token;

    @Value("${gitlab.project-path:1}")
    private String projectId;

    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    public RawProviderResponseSupport.RawProviderResponse issue(int iid) {
        return rawSupport.fetch(() -> restClient.get()
                .uri(baseUrl + "/api/v4/projects/" + projectId + "/issues/" + iid)
                .header("PRIVATE-TOKEN", token)
                .retrieve()
                .body(String.class));
    }
}
