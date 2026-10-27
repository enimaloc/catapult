package fr.enimaloc.catapult.api.experiment;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.experiment.FeedbackRequest;
import fr.enimaloc.catapult.service.experiment.ExperimentFeedbackService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping(ApiPaths.USER)
@RequiredArgsConstructor
public class ApiExperimentFeedbackController {

    private final ExperimentFeedbackService experimentFeedbackService;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/experiment-feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @RequestBody FeedbackRequest body) {
        experimentFeedbackService.submit(userResolver.viewer(jwt), body);
    }
}
