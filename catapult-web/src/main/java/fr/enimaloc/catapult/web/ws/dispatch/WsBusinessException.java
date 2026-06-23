package fr.enimaloc.catapult.web.ws.dispatch;

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
}
