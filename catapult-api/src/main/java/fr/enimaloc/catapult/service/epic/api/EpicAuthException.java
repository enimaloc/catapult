package fr.enimaloc.catapult.service.epic.api;

/** Epic refused an authorization code or a refresh token. Never carries a token, a code or a response body. */
public class EpicAuthException extends RuntimeException {

    public enum Reason {
        /** The authorization code is unknown, already used or expired. */
        CODE_EXPIRED,
        /** The refresh token (or the access token) is no longer accepted. */
        REFRESH_REFUSED
    }

    private final Reason reason;

    public EpicAuthException(Reason reason) {
        super("Epic refused the credentials: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
