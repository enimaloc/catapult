package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.BindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-binding Trigger Warning endpoints owned by the streamer.
 * <p>
 * Ownership is enforced inside {@link BindingService} via
 * {@code findByIdAndUser(bindingId, user)}: any request whose resolved user does not own
 * the binding is silently a no-op (404-like behavior).
 */
@RestController
@RequestMapping("/api/channel/bindings/{bindingId}")
@RequiredArgsConstructor
public class ApiChannelTwController {

    private final BindingService bindingService;
    private final UserAccountRepository userRepo;

    public record SaveBody(Set<String> tws) {}
    public record TwEnabledBody(boolean enabled) {}

    @PostMapping("/tws")
    public ResponseEntity<Void> saveTws(@PathVariable UUID bindingId,
                                        @AuthenticationPrincipal Jwt jwt,
                                        @RequestBody SaveBody body) {
        UserAccount user = currentUser(jwt);
        bindingService.setTwsForBinding(user, bindingId,
                body.tws() == null ? Set.of() : body.tws());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tws/reset")
    public ResponseEntity<Void> resetTws(@PathVariable UUID bindingId,
                                         @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = currentUser(jwt);
        bindingService.resetTws(user, bindingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tw-enabled")
    public ResponseEntity<Void> toggleTwEnabled(@PathVariable UUID bindingId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @RequestBody TwEnabledBody body) {
        UserAccount user = currentUser(jwt);
        bindingService.toggleTwEnabled(user, bindingId, body.enabled());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tws/suggest")
    public Map<String, Set<String>> suggestTws(@PathVariable UUID bindingId,
                                               @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = currentUser(jwt);
        return Map.of("preview", bindingService.previewTws(user, bindingId));
    }

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userRepo.findByTwitchId(twitchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
