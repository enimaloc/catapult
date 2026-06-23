package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;
import lombok.extern.slf4j.Slf4j;
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

    public WsRequestDispatcher(List<RequestHandler> handlers) {
        this.handlers = new HashMap<>(handlers.size());
        for (RequestHandler h : handlers) {
            RequestHandler prev = this.handlers.put(h.action(), h);
            if (prev != null) {
                throw new IllegalStateException("Duplicate WS handler for action " + h.action()
                        + " (" + prev.getClass().getName() + " vs " + h.getClass().getName() + ")");
            }
        }
        log.info("ws request dispatcher registered {} actions: {}", this.handlers.size(), this.handlers.keySet());
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
        return handler.handle(session, params);
    }
}
