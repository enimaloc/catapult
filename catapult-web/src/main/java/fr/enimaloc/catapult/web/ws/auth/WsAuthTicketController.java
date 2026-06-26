package fr.enimaloc.catapult.web.ws.auth;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.security.CatapultWebUser;
import fr.enimaloc.catapult.web.ws.metrics.WsMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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
 * source; the ticket only crosses the wire once and never travels in the URL.
 * The user's JWT (stored in the HttpSession) is captured into the ticket
 * snapshot so the WS dispatch path can propagate it to upstream REST calls.</p>
 */
@Controller
@RequiredArgsConstructor
public class WsAuthTicketController {

    private final WsTicketStore ticketStore;

    @Autowired(required = false)
    private WsMetrics wsMetrics;

    @GetMapping("/ws/auth-ticket")
    @ResponseBody
    public ResponseEntity<TicketResponse> issue(@AuthenticationPrincipal CatapultWebUser user,
                                                HttpServletRequest request) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        Set<String> roles = new HashSet<>(user.getAuthorities().size());
        user.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        String jwt = currentJwt(request);
        String token = ticketStore.issue(user.getId(), roles, jwt);
        if (wsMetrics != null) wsMetrics.recordTicket("issued");
        long ttlSeconds = WsTicketStore.TTL.toSeconds();
        return ResponseEntity.ok(new TicketResponse(token, ttlSeconds));
    }

    private static String currentJwt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object jwt = session.getAttribute(ApiClient.SESSION_JWT_KEY);
        return jwt instanceof String s && !s.isBlank() ? s : null;
    }

    public record TicketResponse(String ticket, long expiresInSeconds) {
    }
}
