package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.channel.ChannelAccessResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.Authz;
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
 * <p>Its {@code userId} is the channel owner's, taken as text: a malformed one answers {@code false} too, not a 400.
 * Returns {@code accessible: false} for unknown owners (no 404, no oracle
 * for account existence). Always {@code accessible: false} for an anonymous
 * call — the channel-events feed is gated to authenticated viewers in V1.</p>
 */
@RestController
@RequiredArgsConstructor
public class ApiChannelAccessController {

    private final UserAccountRepository userAccountRepository;
    private final Authz authz;

    @GetMapping(ApiPaths.USER + "/channel-access")
    public ChannelAccessResponse canAccess(@PathVariable String userId,
                                    @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) return new ChannelAccessResponse(false);

        UUID viewerUuid;
        UUID ownerUuid;
        try {
            viewerUuid = UUID.fromString(jwt.getSubject());
            ownerUuid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return new ChannelAccessResponse(false);
        }

        UserAccount viewer = userAccountRepository.findById(viewerUuid).orElse(null);
        UserAccount owner = userAccountRepository.findById(ownerUuid).orElse(null);
        if (viewer == null || owner == null) return new ChannelAccessResponse(false);

        return new ChannelAccessResponse(authz.canAccess(viewer, owner));
    }
}
