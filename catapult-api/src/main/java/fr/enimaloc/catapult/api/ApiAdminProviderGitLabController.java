package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@Slf4j
@RestController
@RequestMapping("/api/admin/providers/gitlab")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminProviderGitLabController {

    @Value("${gitlab.base-url:https://git.enimaloc.fr}")
    private String baseUrl;

    @Value("${gitlab.token:}")
    private String token;

    @Value("${gitlab.project-path:1}")
    private String projectId;

    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    @GetMapping("/issues/{iid}")
    public RawProviderResponseSupport.RawProviderResponse issue(@PathVariable int iid) {
        return rawSupport.fetch(() -> restClient.get()
                .uri(baseUrl + "/api/v4/projects/" + projectId + "/issues/" + iid)
                .header("PRIVATE-TOKEN", token)
                .retrieve()
                .body(String.class));
    }
}
