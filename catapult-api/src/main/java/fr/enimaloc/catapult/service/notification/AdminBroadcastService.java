package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.metrics.CatapultApiMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * An admin's broadcast on the public Redis bus: the cross-cutting rules ({@link BroadcastValidator}), the per-admin
 * throttle ({@link BroadcastRateLimiter}, 10 a minute), the publication and the audit entry on the admin's own log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBroadcastService {

    private final RedisEventPublisher publisher;
    private final BroadcastValidator validator;
    private final BroadcastRateLimiter rateLimiter;
    private final ActivityLogService activityLog;
    private final CatapultApiMetrics apiMetrics;

    /** 400 when the validator refuses, 429 past the rate limit; both record a failed attempt. */
    public void broadcast(UserAccount admin, BroadcastRequestDto body, String clientIp) {
        // Belt-and-suspenders : Jackson already filters unknown discriminators
        // to a 400, but the validator catches the lifecycle-only name + size cap.
        try {
            validator.validate(body);
        } catch (IllegalArgumentException ex) {
            apiMetrics.recordBroadcastAttempt(body.name(), false);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        if (!rateLimiter.tryAcquire(admin.getId())) {
            apiMetrics.recordBroadcastAttempt(body.name(), false);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "broadcast rate limit exceeded (10/min)");
        }

        publishToBus(body);
        apiMetrics.recordBroadcastAttempt(body.name(), true);
        auditBroadcast(admin, body, clientIp);
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

    private void auditBroadcast(UserAccount admin, BroadcastRequestDto body, String ip) {
        String message = "Diffusion '" + body.name() + "' sur " + body.wsChannel()
                + " (depuis " + ip + ")";
        try {
            activityLog.addEntry(admin.getId(), "INFO", message);
        } catch (Exception ex) {
            // Audit must never break the broadcast — log and move on.
            log.warn("audit log for admin broadcast failed: {}", ex.toString());
        }
    }
}
