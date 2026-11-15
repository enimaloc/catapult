package fr.enimaloc.catapult.service.epic.api;

/** Epic refused an authorization code, a refresh token or an access token. Never carries a token, a code or a response body. */
public class EpicAuthException extends RuntimeException {

    public enum Reason {
        /** The authorization code is unknown, already used or expired (token endpoint). */
        CODE_EXPIRED,
        /** The refresh token is no longer accepted (token endpoint): the owner has to link the account again. */
        REFRESH_REFUSED,
        /** A data API rejected the access token (expired or revoked): refreshing it and retrying may work. */
        ACCESS_REJECTED
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
