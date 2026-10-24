package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The logged-in member's personal API token (the JWT's subject: an admin impersonating a member manages theirs).
 * It only opens the custom bindings API, so it is offered only inside that experiment; revoking stays possible
 * outside it.
 */
@RestController
@RequestMapping("/api/me/api-token")
@RequiredArgsConstructor
public class ApiMeApiTokenController {

    private final ApiUserResolver userResolver;
    private final UserApiTokenService userApiTokenService;
    private final CustomBindingService customBindingService;

    @GetMapping
    public ApiTokenStatusDto status(@AuthenticationPrincipal Jwt jwt) {
        return userApiTokenService.status(enabledMember(jwt));
    }

    /** A new token, replacing the current one at once: the only time it is shown. */
    @PostMapping
    public ApiTokenCreatedDto regenerate(@AuthenticationPrincipal Jwt jwt) {
        return userApiTokenService.regenerate(enabledMember(jwt));
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt) {
        userApiTokenService.revoke(userResolver.viewer(jwt));
    }

    private UserAccount enabledMember(Jwt jwt) {
        UserAccount member = userResolver.viewer(jwt);
        if (!customBindingService.enabled(member)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature not enabled");
        }
        return member;
    }
}
