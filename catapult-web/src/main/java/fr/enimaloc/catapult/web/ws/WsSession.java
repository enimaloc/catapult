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

    public void authenticate(UUID userId, Set<String> roles) {
        authenticate(userId, roles, null);
    }

    public void authenticate(UUID userId, Set<String> roles, String jwt) {
        this.userId = userId;
        this.roles = Set.copyOf(roles);
        this.jwt = jwt;
    }
}
