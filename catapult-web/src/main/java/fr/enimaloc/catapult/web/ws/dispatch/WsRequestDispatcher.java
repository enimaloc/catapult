package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.metrics.WsMetrics;
import fr.enimaloc.catapult.web.ws.ratelimit.WsRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a {@code request}/{@code command} frame to the right
 * {@link RequestHandler} after enforcing auth/admin checks.
 *
 * <p>Handlers are discovered as Spring beans implementing {@link RequestHandler};
 * the dispatcher fails fast on duplicate {@link RequestHandler#action()} names.</p>
 */
@Slf4j
@Component
public class WsRequestDispatcher {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final Map<String, RequestHandler> handlers;
    private final WsRateLimiter rateLimiter;
    private final WsMetrics wsMetrics;

    @Autowired
    public WsRequestDispatcher(List<RequestHandler> handlers, WsRateLimiter rateLimiter, WsMetrics wsMetrics) {
        this.handlers = new HashMap<>(handlers.size());
        this.rateLimiter = rateLimiter;
        this.wsMetrics = wsMetrics;
        for (RequestHandler h : handlers) {
            RequestHandler prev = this.handlers.put(h.action(), h);
            if (prev != null) {
                throw new IllegalStateException("Duplicate WS handler for action " + h.action()
                        + " (" + prev.getClass().getName() + " vs " + h.getClass().getName() + ")");
            }
        }
        log.info("ws request dispatcher registered {} actions: {}", this.handlers.size(), this.handlers.keySet());
    }

    /** Convenience for unit tests that don't care about rate limiting or metrics. */
    public WsRequestDispatcher(List<RequestHandler> handlers, WsRateLimiter rateLimiter) {
        this(handlers, rateLimiter, null);
    }

    /** Convenience for unit tests that don't care about rate limiting. */
    public WsRequestDispatcher(List<RequestHandler> handlers) {
        this(handlers, new WsRateLimiter(), null);
    }

    /**
     * Resolves the handler, runs the auth/admin checks, and delegates. Throws
     * {@link WsBusinessException} for any business rejection so the caller can
     * shape the outbound frame.
     */
    public Object dispatch(WsSession session, String action, Object params) throws Exception {
        if (action == null || action.isBlank()) {
            throw new WsBusinessException("UNKNOWN_ACTION", "Missing action");
        }
        RequestHandler handler = handlers.get(action);
        if (handler == null) {
            throw new WsBusinessException("UNKNOWN_ACTION", "Unknown action: " + action);
        }
        if (handler.requiresAuth() && session.userId().isEmpty()) {
            throw new WsBusinessException("UNAUTHENTICATED", "Action requires authentication");
        }
        if (handler.requiresAdmin() && !session.roles().contains(ROLE_ADMIN)) {
            throw new WsBusinessException("FORBIDDEN", "Action requires admin role");
        }
        String sessionId = session == null ? null : session.id();
        if (!rateLimiter.tryAcquire(sessionId, WsRateLimiter.BUCKET_GLOBAL)) {
            if (wsMetrics != null) wsMetrics.recordRateLimitRejection(WsRateLimiter.BUCKET_GLOBAL);
            throw new WsBusinessException("RATE_LIMITED", "Too many requests");
        }
        if (action.startsWith("search.") && !rateLimiter.tryAcquire(sessionId, WsRateLimiter.BUCKET_SEARCH)) {
            if (wsMetrics != null) wsMetrics.recordRateLimitRejection(WsRateLimiter.BUCKET_SEARCH);
            throw new WsBusinessException("RATE_LIMITED", "Search rate limit exceeded");
        }
        long startNanos = System.nanoTime();
        try {
            Object result = handler.handle(session, params);
            if (wsMetrics != null) {
                wsMetrics.recordActionRequest(action, true);
                wsMetrics.recordActionDuration(action, Duration.ofNanos(System.nanoTime() - startNanos));
            }
            return result;
        } catch (WsBusinessException ex) {
            if (wsMetrics != null) {
                wsMetrics.recordActionRequest(action, false);
                wsMetrics.recordActionError(action, ex.code());
                wsMetrics.recordActionDuration(action, Duration.ofNanos(System.nanoTime() - startNanos));
            }
            throw ex;
        } catch (Exception ex) {
            if (wsMetrics != null) {
                wsMetrics.recordActionRequest(action, false);
                wsMetrics.recordActionDuration(action, Duration.ofNanos(System.nanoTime() - startNanos));
            }
            throw ex;
        }
    }
}
