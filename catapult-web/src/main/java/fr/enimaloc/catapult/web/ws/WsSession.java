package fr.enimaloc.catapult.web.ws;

import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WsSession {

    private final WebSocketSession springSession;
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile UUID userId;
    private volatile Set<String> roles = Set.of();
    private volatile String jwt;
    private volatile String csrfToken;

    public WsSession(WebSocketSession springSession) {
        this.springSession = springSession;
    }

    public String id() {
        return springSession.getId();
    }

    public WebSocketSession springSession() {
        return springSession;
    }

    public Optional<UUID> userId() {
        return Optional.ofNullable(userId);
    }

    public Set<String> roles() {
        return roles;
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    /** Bearer token captured at ticket issuance, propagated to upstream REST calls. */
    public String jwt() {
        return jwt;
    }

    /**
     * Server-issued CSRF token bound to this WS session, set at auth-frame time.
     * Sent to the client via {@code auth.ok} so it can echo it on every HTMX-over-WS
     * mutation request; {@code HtmxWsDispatcher} compares the client-supplied
     * value to this expected value before forwarding to the MVC dispatcher.
     */
    public String csrfToken() {
        return csrfToken;
    }

    public void setCsrfToken(String csrfToken) {
        this.csrfToken = csrfToken;
    }

    public void authenticate(UUID userId, Set<String> roles) {
        authenticate(userId, roles, null);
    }

    public void authenticate(UUID userId, Set<String> roles, String jwt) {
        this.userId = userId;
        this.roles = Set.copyOf(roles);
        this.jwt = jwt;
    }
}
