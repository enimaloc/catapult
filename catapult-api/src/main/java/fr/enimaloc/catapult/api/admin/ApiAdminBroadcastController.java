package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.notification.AdminBroadcastService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import fr.enimaloc.catapult.api.ApiPaths;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint to broadcast typed events on the public Redis pub/sub bus
 * consumed by every {@code catapult-web} WebSocket hub.
 *
 * <p>The request body is a Jackson-polymorphic {@link BroadcastRequestDto}
 * resolved by the {@code name} discriminator (see spec §8). The controller relies on Jackson + {@code @Valid}
 * to enforce the per-subtype shape and hands the rest to {@link AdminBroadcastService} :</p>
 * <ol>
 *   <li>runs {@code BroadcastValidator} for the cross-cutting rules
 *       (name whitelist, payload size, channel allowlist);</li>
 *   <li>throttles per-admin via {@code BroadcastRateLimiter}
 *       (10 broadcasts / minute) — 11th attempt returns {@code 429};</li>
 *   <li>publishes on Redis through {@code RedisEventPublisher};</li>
 *   <li>writes an audit entry on the admin's own activity log.</li>
 * </ol>
 *
 * <p>Returns {@code 202 Accepted}: from the caller's perspective the broadcast
 * is asynchronous (the Redis hand-off is fire-and-forget; downstream fan-out
 * happens on subscriber side).</p>
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/broadcasts")
@RequiredArgsConstructor
public class ApiAdminBroadcastController {

    private final AdminBroadcastService broadcasts;
    private final ApiUserResolver userResolver;

    @PostMapping
    public ResponseEntity<Void> broadcast(@Valid @RequestBody BroadcastRequestDto body,
                                          @AuthenticationPrincipal Jwt jwt,
                                          HttpServletRequest httpRequest) {
        UserAccount admin = userResolver.viewerByTwitchId(jwt);
        broadcasts.broadcast(admin, body, clientIp(httpRequest));
        return ResponseEntity.accepted().build();
    }

    /**
     * Best-effort client IP: trusts {@code X-Forwarded-For} (first hop) when
     * present — catapult is fronted by proxies so the remote addr otherwise
     * points at the reverse proxy.
     */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma < 0 ? xff : xff.substring(0, comma)).trim();
        }
        return req.getRemoteAddr();
    }

}
