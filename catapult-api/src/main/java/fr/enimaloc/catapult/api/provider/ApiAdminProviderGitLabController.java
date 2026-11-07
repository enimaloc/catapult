package fr.enimaloc.catapult.api.provider;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.service.provider.GitLabRawQueries;
import fr.enimaloc.catapult.service.provider.RawProviderResponseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping(ApiPaths.ADMIN + "/providers/gitlab")
@RequiredArgsConstructor
public class ApiAdminProviderGitLabController {

    private final GitLabRawQueries queries;

    @GetMapping("/issues/{iid}")
    public RawProviderResponseSupport.RawProviderResponse issue(@PathVariable int iid) {
        return queries.issue(iid);
    }
}
