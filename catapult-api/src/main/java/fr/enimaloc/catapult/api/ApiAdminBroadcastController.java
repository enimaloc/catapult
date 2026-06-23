package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.dto.BroadcastRequestDto;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.notification.BroadcastRateLimiter;
import fr.enimaloc.catapult.service.notification.BroadcastValidator;
import fr.enimaloc.catapult.service.notification.RedisEventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin endpoint to broadcast typed events on the public Redis pub/sub bus
 * consumed by every {@code catapult-web} WebSocket hub.
 *
 * <p>The request body is a Jackson-polymorphic {@link BroadcastRequestDto}
 * resolved by the {@code name} discriminator (see spec §8). The controller :</p>
 * <ol>
 *   <li>relies on Jackson + {@code @Valid} to enforce the per-subtype shape;</li>
 *   <li>runs {@link BroadcastValidator} for the cross-cutting rules
 *       (name whitelist, payload size, channel allowlist);</li>
 *   <li>throttles per-admin via {@link BroadcastRateLimiter}
 *       (10 broadcasts / minute) — 11th attempt returns {@code 429};</li>
 *   <li>publishes on Redis through {@link RedisEventPublisher};</li>
 *   <li>writes an audit entry on the admin's own activity log.</li>
 * </ol>
 *
 * <p>Returns {@code 202 Accepted}: from the caller's perspective the broadcast
 * is asynchronous (the Redis hand-off is fire-and-forget; downstream fan-out
 * happens on subscriber side).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/broadcast")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ApiAdminBroadcastController {

    private final RedisEventPublisher publisher;
    private final BroadcastValidator validator;
    private final BroadcastRateLimiter rateLimiter;
    private final ActivityLogService activityLog;
    private final UserAccountRepository userRepo;

    @PostMapping
    public ResponseEntity<Void> broadcast(@Valid @RequestBody BroadcastRequestDto body,
                                          @AuthenticationPrincipal Jwt jwt,
                                          HttpServletRequest httpRequest) {
        UserAccount admin = currentUser(jwt);

        // Belt-and-suspenders : Jackson already filters unknown discriminators
        // to a 400, but the validator catches the lifecycle-only name + size cap.
        try {
            validator.validate(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        if (!rateLimiter.tryAcquire(admin.getId())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "broadcast rate limit exceeded (10/min)");
        }

        publishToBus(body);
        auditBroadcast(admin, body, httpRequest);

        return ResponseEntity.accepted().build();
    }

    private void publishToBus(BroadcastRequestDto body) {
        String channel = body.redisChannel();
        if (RedisEventPublisher.CHANNEL_ADMIN.equals(channel)) {
            publisher.publishAdmin(body.name(), body.data());
        } else {
            // Whitelist is global-or-admin (see BroadcastValidator) — anything else
            // would already have been rejected above.
            publisher.publishGlobal(body.name(), body.data());
        }
    }

    private void auditBroadcast(UserAccount admin, BroadcastRequestDto body, HttpServletRequest req) {
        String ip = clientIp(req);
        String message = "Diffusion '" + body.name() + "' sur " + body.wsChannel()
                + " (depuis " + ip + ")";
        try {
            activityLog.addEntry(admin.getId(), "INFO", message);
        } catch (Exception ex) {
            // Audit must never break the broadcast — log and move on.
            log.warn("audit log for admin broadcast failed: {}", ex.toString());
        }
    }

    /**
     * Best-effort client IP: trusts {@code X-Forwarded-For} (first hop) when
     * present — catapult is fronted by nginx so the remote addr otherwise
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

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userRepo.findByTwitchId(twitchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
