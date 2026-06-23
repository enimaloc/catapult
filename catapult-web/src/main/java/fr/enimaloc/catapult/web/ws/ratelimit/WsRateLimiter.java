package fr.enimaloc.catapult.web.ws.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-WebSocket-session token-bucket rate limiter.
 *
 * <p>Three named buckets exist out of the box, mirroring the spec §10:</p>
 * <ul>
 *   <li>{@code search}  — 10 req/s (refill 10/s, capacity 10)</li>
 *   <li>{@code htmx}    — 30 req/s (refill 30/s, capacity 30)</li>
 *   <li>{@code global}  — 100 frames/s (refill 100/s, capacity 100)</li>
 * </ul>
 *
 * <p>Buckets are lazily created per (sessionId, bucketName) tuple and live in
 * memory; call {@link #cleanup(String)} on session close to release them.</p>
 */
@Component
public class WsRateLimiter {

    public static final String BUCKET_SEARCH = "search";
    public static final String BUCKET_HTMX = "htmx";
    public static final String BUCKET_GLOBAL = "global";

    /** sessionId -> bucketName -> Bucket */
    private final Map<String, Map<String, Bucket>> bucketsBySession = new ConcurrentHashMap<>();

    /** Returns true if the call may proceed; false if rate-limited. */
    public boolean tryAcquire(String sessionId, String bucketName, int tokens) {
        if (sessionId == null) return true; // bucketless calls (tests, internal) pass.
        Bucket bucket = bucketsBySession
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(bucketName, this::newBucket);
        return bucket.tryConsume(tokens);
    }

    /** Convenience: consume 1 token. */
    public boolean tryAcquire(String sessionId, String bucketName) {
        return tryAcquire(sessionId, bucketName, 1);
    }

    /** Drop all buckets for a session — call on disconnect. */
    public void cleanup(String sessionId) {
        if (sessionId != null) bucketsBySession.remove(sessionId);
    }

    int trackedSessions() {
        return bucketsBySession.size();
    }

    private Bucket newBucket(String name) {
        return switch (name) {
            case BUCKET_SEARCH -> simpleBucket(10);
            case BUCKET_HTMX -> simpleBucket(30);
            case BUCKET_GLOBAL -> simpleBucket(100);
            // Unknown buckets default to a permissive 100/s — keeps misconfigured callers from breaking.
            default -> simpleBucket(100);
        };
    }

    private static Bucket simpleBucket(long perSecond) {
        Bandwidth band = Bandwidth.builder()
                .capacity(perSecond)
                .refillGreedy(perSecond, Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(band).build();
    }
}
