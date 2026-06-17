package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.AlphaInvite;
import fr.enimaloc.catapult.domain.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/invite")
@RequiredArgsConstructor
public class ApiInviteController {

    private final InviteService inviteService;
    private final UserAccountRepository userAccountRepository;

    @Value("${app.web-url:}")
    private String webUrl;

    @GetMapping
    public InvitePageData page(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = resolveUser(jwt);
        Optional<AlphaInvite> inviteOpt = inviteService.getInvite(user);
        if (inviteOpt.isEmpty()) {
            return new InvitePageData(false, null, null, null, null, List.of());
        }
        AlphaInvite invite = inviteOpt.get();
        List<AlphaInviteRedemption> redemptions = inviteService.getRedemptions(invite);
        String inviteUrl = (webUrl != null && !webUrl.isBlank() ? webUrl : "") + "/join?invite=" + invite.getCode();
        return new InvitePageData(true, invite.getId(), invite.getCode(), inviteUrl,
            invite.getRegeneratedAt(), redemptions.stream()
                .map(r -> new RedemptionDto(r.getInviteeTwitchId(), r.getRedeemedAt()))
                .toList());
    }

    @PostMapping("/regenerate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void regenerate(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = resolveUser(jwt);
        inviteService.regenerateCode(user);
    }

    private UserAccount resolveUser(Jwt jwt) {
        return userAccountRepository.findById(UUID.fromString(jwt.getSubject()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public record InvitePageData(boolean canInvite, UUID inviteId, String code, String inviteUrl,
                                 Instant regeneratedAt, List<RedemptionDto> redemptions) {}

    public record RedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}
}
