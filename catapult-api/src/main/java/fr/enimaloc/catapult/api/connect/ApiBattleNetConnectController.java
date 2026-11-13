package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.connect.BattleNetLinkStateResponse;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.battlenet.BattleNetFriendService;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetLinkStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * A member's own Battle.net link. Linking itself goes through Blizzard's OAuth flow (see
 * {@code CatapultOAuth2UserService}); this only states the link and leaves it. Deliberately not transactional:
 * unenrolling commits its own transaction before the bot and the dashboards are told.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/links/battlenet")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("battlenet.enabled")
public class ApiBattleNetConnectController {

    private final BattleNetFriendService friendService;
    private final BattleNetLinkStateService linkStates;
    private final BattleNetGateService gateService;
    private final UserAccountRepository userAccountRepository;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public BattleNetLinkStateResponse status(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return linkStates.state(currentUser(jwt));
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = currentUser(jwt);
        if (!gateService.isAvailableFor(user)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Battle.net is not available for this account");
        }
        friendService.unenroll(user);
    }

    private UserAccount currentUser(Jwt jwt) {
        UUID caller = UUID.fromString(ApiUserResolver.requireJwt(jwt).getSubject());
        return userAccountRepository.findById(caller)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
