package fr.enimaloc.catapult.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Fans out change notifications to every browser tab with a live connection on a given
 * channel's dashboard. Handshakes at {@code /ws/channel/{username}} (username extracted by
 * {@link ChannelWebSocketConfig}'s interceptor) join the session under that username;
 * publishing a {@link ChannelUpdatedEvent} then pushes {@code {"scope": "..."}} to each of
 * them. The payload never carries rendered content — the client re-fetches
 * {@code /spa/channel/{username}} and patches only the DOM subtree the scope names, so this
 * handler stays free of any page-rendering concern. Listening for the event rather than being
 * called directly keeps this handler decoupled from whatever publishes the change — today
 * MockApiService/MockAdminController, later the real backend.
 */
@Slf4j
@Component
public class ChannelWebSocketHandler extends TextWebSocketHandler {
    public static final String USERNAME_ATTRIBUTE = "channelUsername";

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByUsername = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = username(session);
        if (username == null) {
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        sessionsByUsername.computeIfAbsent(username, ignored -> new CopyOnWriteArraySet<>()).add(session);
        log.trace("[{}] channel WS connected ({})", username, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = username(session);
        if (username == null) {
            return;
        }
        sessionsByUsername.computeIfPresent(username, (ignored, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
        log.trace("[{}] channel WS disconnected ({})", username, session.getId());
    }

    /** Notifies every connected session for the event's username that its scope changed. */
    @EventListener
    public void onChannelUpdated(ChannelUpdatedEvent event) {
        String username = event.username();
        Set<WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage("{\"scope\":\"" + event.scope() + "\"}");
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.debug("[{}] failed to push '{}' to session {}", username, event.scope(), session.getId(), e);
            }
        }
    }

    private static String username(WebSocketSession session) {
        Object value = session.getAttributes().get(USERNAME_ATTRIBUTE);
        return value instanceof String s ? s : null;
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // Nothing more we can do with a session that won't close cleanly.
        }
    }
}
