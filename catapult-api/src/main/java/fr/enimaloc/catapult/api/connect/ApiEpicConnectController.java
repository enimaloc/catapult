package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.connect.EpicLinkStateResponse;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.epic.EpicFriendService;
import fr.enimaloc.catapult.service.epic.EpicGateService;
import fr.enimaloc.catapult.service.epic.EpicLinkStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * A member's own Epic link. Linking itself goes through the Epic OAuth login; here the member reads the link, has
 * the bot send its friend request again, or unlinks. Deliberately not transactional: the friend service commits its
 * own transactions before the bot and the dashboards are told.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/links/epic")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("epic.enabled")
public class ApiEpicConnectController {

    private final EpicFriendService friendService;
    private final EpicLinkStateService linkStates;
    private final EpicGateService gateService;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public EpicLinkStateResponse status(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return linkStates.state(userResolver.viewer(jwt));
    }

    /** Has the bot send its friend request again (a link still waiting only). */
    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/resend")
    public EpicLinkStateResponse resend(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = gated(jwt);
        friendService.resendInvite(user);
        return linkStates.state(user);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        friendService.unenroll(gated(jwt));
    }

    private UserAccount gated(Jwt jwt) {
        UserAccount user = userResolver.viewer(jwt);
        if (!gateService.isAvailableFor(user)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Epic is not available for this account");
        }
        return user;
    }
}
