package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.connect.AnkamaLinkStateResponse;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.ankama.AnkamaFriendService;
import fr.enimaloc.catapult.service.ankama.AnkamaFriendService.AnkamaEnrollmentException;
import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.ankama.AnkamaLinkStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * A member's own Ankama link: the streamer gives their {@code name#tag}, a bot invites them in the Ankama launcher
 * chat. Deliberately not transactional: enrolling and unenrolling commit their own transaction before the bot and
 * the dashboards are told.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/links/ankama")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("ankama.enabled")
public class ApiAnkamaConnectController {

    private final AnkamaFriendService friendService;
    private final AnkamaLinkStateService linkStates;
    private final AnkamaGateService gateService;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public AnkamaLinkStateResponse status(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return linkStates.state(userResolver.viewer(jwt));
    }

    /**
     * Links the member to a bot under {@code nameTag}. 403 when the feature is closed (before any validation), 400 for
     * a bad {@code name#tag}, 409 when no bot has room ({@code reason: FULL}) or the Ankama account belongs to another
     * member ({@code reason: ALREADY_LINKED}), 503 when no bot is usable.
     */
    @PreAuthorize("@authz.isSelf(#userId)")
    @PutMapping
    public AnkamaLinkStateResponse enroll(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt,
                                          @RequestBody Map<String, String> body) {
        UserAccount user = gated(jwt);
        try {
            friendService.enroll(user, body.get("nameTag"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AnkamaEnrollmentException e) {
            throw switch (e.getReason()) {
                case NO_CAPACITY -> new ApiProblem(ErrorCode.CONFLICT, e.getMessage(),
                        Map.of("reason", AnkamaLinkStateService.FULL));
                case ALREADY_LINKED -> new ApiProblem(ErrorCode.CONFLICT, e.getMessage(),
                        Map.of("reason", "ALREADY_LINKED"));
                case NO_ACCOUNT_AVAILABLE -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No Ankama service account is available, contact an administrator");
            };
        }
        return linkStates.state(user);
    }

    /** Has the bot invite the member again (a link still waiting only). */
    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/resend")
    public AnkamaLinkStateResponse resend(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
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
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Ankama is not available for this account");
        }
        return user;
    }
}
