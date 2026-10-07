package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.common.dto.channel.ChannelAccessResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Lightweight access-probe endpoint used by catapult-web's ChannelResolver when
 * the browser subscribes to {@code channel.viewed.<ownerId>}: it must decide
 * whether the authenticated viewer is allowed to receive events for that
 * channel before binding the subscription.
 *
 * <p>Returns {@code accessible: false} for unknown owners (no 404, no oracle
 * for account existence). Always {@code accessible: false} for an anonymous
 * call — the channel-events feed is gated to authenticated viewers in V1.</p>
 */
@RestController
@RequiredArgsConstructor
public class ApiChannelAccessController {

    private final UserAccountRepository userAccountRepository;
    private final ChannelAccessService channelAccessService;

    @GetMapping("/api/users/{ownerId}/channel-access")
    public ChannelAccessResponse canAccess(@PathVariable String ownerId,
                                    @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) return new ChannelAccessResponse(false);

        UUID viewerUuid;
        UUID ownerUuid;
        try {
            viewerUuid = UUID.fromString(jwt.getSubject());
            ownerUuid = UUID.fromString(ownerId);
        } catch (IllegalArgumentException e) {
            return new ChannelAccessResponse(false);
        }

        UserAccount viewer = userAccountRepository.findById(viewerUuid).orElse(null);
        UserAccount owner = userAccountRepository.findById(ownerUuid).orElse(null);
        if (viewer == null || owner == null) return new ChannelAccessResponse(false);

        return new ChannelAccessResponse(channelAccessService.canAccess(viewer, owner));
    }
}
