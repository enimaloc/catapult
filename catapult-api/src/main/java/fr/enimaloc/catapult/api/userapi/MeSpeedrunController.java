package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentityResolver;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunPersonalService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The token owner's speedrun.com account (found by their key, their Twitch login or the name they declared), with
 * their personal bests and runs waiting for verification on every game. Personal data: only ever served here, under
 * {@code /api/me}, never on the public routes. Version 3 and later only.
 */
@RestController
@RequestMapping(path = ApiV2.ME_SPEEDRUN_PATH, version = ApiV2.VERSION_3 + "+")
@RequiredArgsConstructor
@Tag(name = "Me — speedrun", description = "Your speedrun.com account, personal bests and pending runs, by your "
        + "personal API token (Authorization: Bearer cat_… or catr_…).")
public class MeSpeedrunController {

    private final SpeedrunIdentityResolver identityResolver;
    private final SpeedrunPersonalService personalService;
    private final ApiUserResolver userResolver;

    @GetMapping
    @Operation(summary = "Get your speedrun.com account, personal bests and pending runs",
            description = "account (name, url, source: api_key, twitch or declared), personalBests (full-game, every "
                    + "game, each with its game, category, place, time in seconds, date and run page) and pendingRuns "
                    + "(runs waiting for verification, every game). A list is null when it could not be read.")
    @ApiResponse(responseCode = "200", description = "Your account and runs.")
    @ApiResponse(responseCode = "404", description = "No speedrun.com account found for your channel, or "
            + "speedrun.com disabled.", content = @Content)
    @ApiResponse(responseCode = "429", description = "speedrun.com is asked too often right now; retry after "
            + "Retry-After seconds.", content = @Content)
    public SpeedrunAccountResponse speedrun(@AuthenticationPrincipal UUID userId) {
        UserAccount user = userResolver.tokenOwner(userId);
        if (!personalService.enabled()) {
            throw new ApiProblem(ErrorCode.NOT_FOUND, "speedrun.com is disabled");
        }
        SpeedrunIdentity account = identityResolver.resolve(user, FetchBudget.UNLIMITED)
                .orElseThrow(() -> new ApiProblem(ErrorCode.NOT_FOUND, "No speedrun.com account found"));
        return new SpeedrunAccountResponse(GameInfoV3Response.SpeedrunObject.Account.of(account),
                personalService.personalBests(account.userId(), null, FetchBudget.UNLIMITED).orElse(null),
                personalService.pendingRuns(account, null, personalService.apiKey(user, account),
                        FetchBudget.UNLIMITED).orElse(null));
    }

    /** A member's speedrun.com account and their runs on every game. */
    public record SpeedrunAccountResponse(GameInfoV3Response.SpeedrunObject.Account account,
                                          List<SpeedrunRun> personalBests, List<SpeedrunRun> pendingRuns) {
    }
}
