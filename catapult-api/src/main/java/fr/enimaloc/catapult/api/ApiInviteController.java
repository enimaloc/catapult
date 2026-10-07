package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.common.dto.InviteRedemptionDto;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.InviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/invite")
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

    @GetMapping
    public InvitePageData page(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewer(jwt);
        experimentService.track(user, EXPERIMENT_KEY, EVENT_PAGE_VIEW);
        Optional<AlphaInvite> inviteOpt = inviteService.getInvite(user);
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

    @PostMapping("/regenerate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void regenerate(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewer(jwt);
        inviteService.regenerateCode(user);
        experimentService.track(user, EXPERIMENT_KEY, EVENT_LINK_REGENERATED);
    }

}
