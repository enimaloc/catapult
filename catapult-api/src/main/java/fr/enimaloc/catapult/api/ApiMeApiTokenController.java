package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in member's personal API token (the JWT's subject: an admin impersonating a member manages theirs).
 * The full token only opens the custom bindings API, so it is offered only inside that experiment; revoking stays
 * possible outside it. The read-only token under {@code /read} opens only the {@code GET /api/me/**} routes and is
 * offered to every member.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/api-token")
@RequiredArgsConstructor
public class ApiMeApiTokenController {

    private final ApiUserResolver userResolver;
    private final UserApiTokenService userApiTokenService;
    private final CustomBindingService customBindingService;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public ApiTokenStatusDto status(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return userApiTokenService.status(enabledMember(jwt), ApiTokenScope.FULL);
    }

    /** A new token, replacing the current one at once: the only time it is shown. */
    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping
    public ApiTokenCreatedDto regenerate(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return userApiTokenService.regenerate(enabledMember(jwt), ApiTokenScope.FULL);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        userApiTokenService.revoke(userResolver.viewer(jwt), ApiTokenScope.FULL);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping("/read")
    public ApiTokenStatusDto readStatus(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return userApiTokenService.status(userResolver.viewer(jwt), ApiTokenScope.READ);
    }

    /** A new read-only token, replacing the current one at once: the only time it is shown. */
    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/read")
    public ApiTokenCreatedDto regenerateRead(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return userApiTokenService.regenerate(userResolver.viewer(jwt), ApiTokenScope.READ);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeRead(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        userApiTokenService.revoke(userResolver.viewer(jwt), ApiTokenScope.READ);
    }

    private UserAccount enabledMember(Jwt jwt) {
        UserAccount member = userResolver.viewer(jwt);
        if (!customBindingService.enabled(member)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Feature not enabled");
        }
        return member;
    }
}
