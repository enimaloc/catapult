package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.SaveBody;
import fr.enimaloc.catapult.common.dto.channel.TwEnabledBody;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-binding Trigger Warning endpoints owned by the streamer.
 * <p>
 * Ownership is enforced inside {@link BindingService} via
 * {@code findByIdAndUser(bindingId, user)}: any request whose resolved user does not own
 * the binding is silently a no-op (404-like behavior). Each change goes out to the owner's dashboards
 * as the binding's new state.
 */
@RestController
@RequestMapping("/api/channel/bindings/{bindingId}")
@RequiredArgsConstructor
public class ApiChannelTwController {

    private final BindingService bindingService;
    private final ApiUserResolver userResolver;
    private final ChannelEventPublisher channelEventPublisher;

    @PostMapping("/tws")
    public ResponseEntity<Void> saveTws(@PathVariable UUID bindingId,
                                        @AuthenticationPrincipal Jwt jwt,
                                        @RequestBody SaveBody body) {
        UserAccount user = userResolver.viewerByTwitchId(jwt);
        bindingService.setTwsForBinding(user, bindingId,
                body.tws() == null ? Set.of() : body.tws());
        publishBinding(user, bindingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tws/reset")
    public ResponseEntity<Void> resetTws(@PathVariable UUID bindingId,
                                         @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userResolver.viewerByTwitchId(jwt);
        bindingService.resetTws(user, bindingId);
        publishBinding(user, bindingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tw-enabled")
    public ResponseEntity<Void> toggleTwEnabled(@PathVariable UUID bindingId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @RequestBody TwEnabledBody body) {
        UserAccount user = userResolver.viewerByTwitchId(jwt);
        bindingService.toggleTwEnabled(user, bindingId, body.enabled());
        publishBinding(user, bindingId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tws/suggest")
    public Map<String, Set<String>> suggestTws(@PathVariable UUID bindingId,
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
