package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.common.dto.InviteRedemptionDto;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.access.InviteService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping(ApiPaths.USER + "/invite")
@RequiredArgsConstructor
public class ApiInviteController {

    static final String EXPERIMENT_KEY        = "invite-button-placement";
    static final String EVENT_PAGE_VIEW       = "invite_page_view";
    static final String EVENT_LINK_REGENERATED = "invite_link_regenerated";

    private final InviteService inviteService;
    private final ApiUserResolver userResolver;
    private final ExperimentService experimentService;

    @Value("${app.web-url:}")
    private String webUrl;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public InvitePageData page(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewer(jwt);
        experimentService.track(user, EXPERIMENT_KEY, EVENT_PAGE_VIEW);
        return pageOf(user, inviteService.getInvite(user));
    }

    /** The page with its new code, for the member's page to show it without reading the page again. */
    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/regenerate")
    public InvitePageData regenerate(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewer(jwt);
        Optional<AlphaInvite> invite = inviteService.regenerateCode(user);
        experimentService.track(user, EXPERIMENT_KEY, EVENT_LINK_REGENERATED);
        return pageOf(user, invite);
    }

    private InvitePageData pageOf(UserAccount user, Optional<AlphaInvite> inviteOpt) {
        if (inviteOpt.isEmpty()) {
            return new InvitePageData(false, null, null, null, null, List.of());
        }
        AlphaInvite invite = inviteOpt.get();
        List<AlphaInviteRedemption> redemptions = inviteService.getRedemptions(invite);
        String inviteUrl = (webUrl != null && !webUrl.isBlank() ? webUrl : "") + "/join?invite=" + invite.getCode();
        return new InvitePageData(true, invite.getId(), invite.getCode(), inviteUrl,
            invite.getRegeneratedAt(), redemptions.stream()
                .map(r -> new InviteRedemptionDto(r.getInviteeTwitchId(), r.getRedeemedAt()))
                .toList());
    }

}
