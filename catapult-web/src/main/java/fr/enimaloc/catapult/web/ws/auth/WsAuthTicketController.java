package fr.enimaloc.catapult.web.ws.auth;

import fr.enimaloc.catapult.security.CatapultWebUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashSet;
import java.util.Set;

/**
 * Hands out a short-lived ticket the browser can present in the WebSocket
 * {@code auth} frame to upgrade an anonymous session to an authenticated one.
 *
 * <p>The session cookie established by Spring Security is the authentication
 * source; the ticket only crosses the wire once and never travels in the URL.</p>
 */
@Controller
@RequiredArgsConstructor
public class WsAuthTicketController {

    private final WsTicketStore ticketStore;

    @GetMapping("/ws/auth-ticket")
    @ResponseBody
    public ResponseEntity<TicketResponse> issue(@AuthenticationPrincipal CatapultWebUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        Set<String> roles = new HashSet<>(user.getAuthorities().size());
        user.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        String token = ticketStore.issue(user.getId(), roles);
        long ttlSeconds = WsTicketStore.TTL.toSeconds();
        return ResponseEntity.ok(new TicketResponse(token, ttlSeconds));
    }

    public record TicketResponse(String ticket, long expiresInSeconds) {
    }
}
