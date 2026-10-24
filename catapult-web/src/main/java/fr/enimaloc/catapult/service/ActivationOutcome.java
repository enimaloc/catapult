package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.common.error.ErrorCode;

/**
 * How catapult-api answered the activation of a custom binding, from its status and, for a 409, its error
 * {@code code}. {@link #status()} is the HTTP status the page is answered with.
 */
public enum ActivationOutcome {
    /** The category is on Twitch and the binding is the active one. */
    APPLIED(204, null),
    /** 409: no Twitch token, or Twitch refused it: the member reconnects their account. */
    TWITCH_UNAVAILABLE(409, ErrorCode.TWITCH_UNAVAILABLE),
    /** 409: the bot is paused on the channel. */
    BOT_DISABLED(409, ErrorCode.BOT_DISABLED),
    /** The member may not use custom bindings (gate closed, not the owner). */
    FORBIDDEN(403, null),
    /** No such binding or channel. */
    NOT_FOUND(404, null),
    /** Twitch failed or refused the update, or catapult-api failed or could not be reached. */
    FAILED(502, null);

    private final int status;
    private final ErrorCode code;

    ActivationOutcome(int status, ErrorCode code) {
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** The error code to tell the page, null when the status says it all. */
    public ErrorCode code() {
        return code;
    }

    /** What catapult-api's {@code status} (and 409 {@code code}) means; anything unexpected is a failure. */
    public static ActivationOutcome of(int status, ErrorCode code) {
        if (status >= 200 && status < 300) return APPLIED;
        return switch (status) {
            case 409 -> code == ErrorCode.BOT_DISABLED ? BOT_DISABLED
                    : code == ErrorCode.TWITCH_UNAVAILABLE ? TWITCH_UNAVAILABLE : FAILED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            default -> FAILED;
        };
    }
}
