package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.SaveBody;
import fr.enimaloc.catapult.common.dto.channel.TwEnabledBody;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-binding Trigger Warning endpoints owned by the streamer: a member's own routes ({@code @authz.isSelf}), not a
 * channel's, so a moderator cannot change the owner's TWs.
 * <p>
 * Ownership is enforced inside {@link BindingService} via
 * {@code findByIdAndUser(bindingId, user)}: any request whose resolved user does not own
 * the binding is silently a no-op (404-like behavior). Each change goes out to the owner's dashboards
 * as the binding's new state.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/bindings/{bindingId}")
@RequiredArgsConstructor
public class ApiChannelTwController {

    private final BindingService bindingService;
    private final ApiUserResolver userResolver;
    private final ChannelEventPublisher channelEventPublisher;

    @PreAuthorize("@authz.isSelf(#userId)")
    @PutMapping("/tws")
    public ResponseEntity<Void> saveTws(@PathVariable UUID userId,
                                        @PathVariable UUID bindingId,
                                        @AuthenticationPrincipal Jwt jwt,
                                        @RequestBody SaveBody body) {
        UserAccount user = userResolver.viewerByTwitchId(jwt);
        bindingService.setTwsForBinding(user, bindingId,
                body.tws() == null ? Set.of() : body.tws());
        publishBinding(user, bindingId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping("/tws")
    public ResponseEntity<Void> resetTws(@PathVariable UUID userId,
                                         @PathVariable UUID bindingId,
                                         @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewerByTwitchId(jwt);
        bindingService.resetTws(user, bindingId);
        publishBinding(user, bindingId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PutMapping("/tw-enabled")
    public ResponseEntity<Void> toggleTwEnabled(@PathVariable UUID userId,
                                                @PathVariable UUID bindingId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @RequestBody TwEnabledBody body) {
        UserAccount user = userResolver.viewerByTwitchId(jwt);
        bindingService.toggleTwEnabled(user, bindingId, body.enabled());
        publishBinding(user, bindingId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping("/tws/suggestions")
    public Map<String, Set<String>> suggestTws(@PathVariable UUID userId,
                                               @PathVariable UUID bindingId,
                                               @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewerByTwitchId(jwt);
        return Map.of("preview", bindingService.previewTws(user, bindingId));
    }

    /** Tells the owner's dashboards about the binding's new state (nothing if it isn't theirs). */
    private void publishBinding(UserAccount user, UUID bindingId) {
        bindingService.findBinding(user, bindingId)
                .ifPresent(binding -> channelEventPublisher.bindingUpserted(user.getId(), binding));
    }
}
