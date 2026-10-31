package fr.enimaloc.catapult.service.notification;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-admin rate limit on the broadcast endpoint (spec §10).
 *
 * <p>The token bucket is sized at 10 broadcasts per minute, refilling greedily:
 * a quiet admin keeps a full burst available, a chatty one is throttled
 * immediately once they exceed the rate. Buckets are keyed by admin user id
 * and kept in-process — broadcasts are infrequent and the cap on entries
 * (one per admin) makes memory usage trivial.</p>
 */
@Component
public class BroadcastRateLimiter {

    /** 10 broadcasts per minute per admin. */
    public static final long PER_MINUTE = 10;

    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    /** Returns true if the broadcast may proceed; false if the admin must wait. */
    public boolean tryAcquire(UUID adminUserId) {
        if (adminUserId == null) {
            // No identity → can't enforce a per-admin limit. The controller already
            // gates on hasRole('ADMIN') so this path is only hit if someone wires
            // the limiter into a non-authenticated flow.
            return true;
        }
        return buckets.computeIfAbsent(adminUserId, k -> newBucket()).tryConsume(1);
    }

    private static Bucket newBucket() {
        Bandwidth band = Bandwidth.builder()
                .capacity(PER_MINUTE)
                .refillGreedy(PER_MINUTE, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(band).build();
    }
}
