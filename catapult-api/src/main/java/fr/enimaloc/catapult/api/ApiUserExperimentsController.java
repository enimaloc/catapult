package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.VariantResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/experiments/me")
@RequiredArgsConstructor
public class ApiUserExperimentsController {

    private final ExperimentService experimentService;
    private final ApiUserResolver userResolver;

    /**
     * Returns the variant key assigned to the current user for the given experiment.
     * Triggers assignment if the user is eligible but not yet assigned.
     * Returns {"variant": null} when no assignment applies (experiment paused/ended/excluded).
     */
    @Transactional
    @GetMapping("/variant/{key}")
    public VariantResponse getVariant(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewer(jwt);
        return new VariantResponse(
            experimentService.getVariant(user, key).map(v -> v.getKey()).orElse(null)
        );
    }
}
