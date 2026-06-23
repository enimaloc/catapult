package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.ratelimit.WsRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    @Autowired
    public WsRequestDispatcher(List<RequestHandler> handlers, WsRateLimiter rateLimiter) {
        this.handlers = new HashMap<>(handlers.size());
        this.rateLimiter = rateLimiter;
        for (RequestHandler h : handlers) {
            RequestHandler prev = this.handlers.put(h.action(), h);
            if (prev != null) {
                throw new IllegalStateException("Duplicate WS handler for action " + h.action()
                        + " (" + prev.getClass().getName() + " vs " + h.getClass().getName() + ")");
            }
        }
        log.info("ws request dispatcher registered {} actions: {}", this.handlers.size(), this.handlers.keySet());
    }

    /** Convenience for unit tests that don't care about rate limiting. */
    public WsRequestDispatcher(List<RequestHandler> handlers) {
        this(handlers, new WsRateLimiter());
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
            throw new WsBusinessException("RATE_LIMITED", "Too many requests");
        }
        if (action.startsWith("search.") && !rateLimiter.tryAcquire(sessionId, WsRateLimiter.BUCKET_SEARCH)) {
            throw new WsBusinessException("RATE_LIMITED", "Search rate limit exceeded");
        }
        return handler.handle(session, params);
    }
}
