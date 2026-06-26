package fr.enimaloc.catapult.web.ws.dispatch;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown by a {@link RequestHandler} or the dispatcher when an incoming
 * request cannot be honoured for a business reason (unauthorised, forbidden,
 * rate-limited, validation failure). The dispatcher converts these to
 * {@code response ok:false} or {@code error} frames depending on the inbound
 * message type.
 */
public class WsBusinessException extends RuntimeException {
    private final String code;

    public WsBusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Converts a {@link ResponseStatusException} to a {@link WsBusinessException}
     * by mapping HTTP status codes to WS error codes.
     */
    public static WsBusinessException fromResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == HttpStatus.FORBIDDEN) {
            return new WsBusinessException("FORBIDDEN", e.getReason() != null ? e.getReason() : "Forbidden");
        }
        if (status == HttpStatus.NOT_FOUND) {
            return new WsBusinessException("NOT_FOUND", e.getReason() != null ? e.getReason() : "Not found");
        }
        return new WsBusinessException("INTERNAL", e.getReason() != null ? e.getReason() : "Internal error");
    }
}
