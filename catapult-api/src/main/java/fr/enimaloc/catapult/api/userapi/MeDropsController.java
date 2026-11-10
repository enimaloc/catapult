package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.drops.DropsCatalog;
import fr.enimaloc.catapult.service.drops.DropsGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The Twitch drops campaigns running for the token owner's channel, from Catapult's copy of ttvdrops (refreshed every
 * 15 minutes): on the category of the game the channel plays now (as /api/me/game), or on {@code categoryId}.
 * Refused with feature_disabled while the drops experiment is closed to the channel.
 */
@RestController
@RequestMapping(path = ApiV2.ME_DROPS_PATH, version = ApiV2.VERSION)
@RequiredArgsConstructor
@Tag(name = "Me — drops", description = "The Twitch drops campaigns running for your channel, by your personal API "
        + "token (Authorization: Bearer cat_… or catr_…).")
public class MeDropsController {

    private final DropsGate dropsGate;
    private final DropsCatalog dropsCatalog;
    private final GameStateService gameStateService;
    private final GameBindingLookup gameBindingLookup;
    private final ApiUserResolver userResolver;

    @GetMapping
    @Operation(summary = "The drops campaigns running for your channel on its current category, soonest end first",
            description = "Empty while no game is detected, or when the game's category has no campaign running for "
                    + "your channel; 403 feature_disabled while drops aren't offered to your channel.")
    public List<DropCampaignResponse> drops(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "A Twitch category id, instead of the current game's", example = "1282866981")
            @RequestParam(name = "categoryId", required = false) String categoryId) {
        UserAccount user = userResolver.tokenOwner(userId);
        if (!dropsGate.isAvailableFor(user)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Drops not available for this channel");
        }
        String category = categoryId != null && !categoryId.isBlank() ? categoryId : currentCategory(user);
        if (category == null) return List.of();
        return dropsCatalog.runningFor(user.getTwitchId(), category).stream().map(DropCampaignResponse::of).toList();
    }

    /** The category of the game the channel plays now, null without one. */
    private String currentCategory(UserAccount user) {
        return gameStateService.getLastKnownGame(user)
                .flatMap(detected -> gameBindingLookup.findByDetectedGame(user, detected))
                .map(GameBinding::getTwitchGameId)
                .orElse(null);
    }
}
