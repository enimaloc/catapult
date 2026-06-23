package fr.enimaloc.catapult.web.ws.codec.msg;

import java.util.List;
import java.util.UUID;

/**
 * Ack of a successful {@code auth} frame.
 *
 * <p>{@code csrfToken} is a per-session secret issued by the server (32 bytes of
 * SecureRandom, URL-safe base64). The client must echo it on every HTMX-over-WS
 * mutation request (in the request's {@code csrfToken} field). The server
 * verifies the echoed value before invoking the dispatcher — the client never
 * gets to choose what the expected token is, which is the whole point of the
 * CSRF defence.</p>
 */
public record AuthOkMessage(UUID userId, List<String> roles, String csrfToken) implements WsOutgoing {
}
