package fr.enimaloc.catapult.common.error;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The stable contract of catapult-api's problems: the {@code code} extension of every
 * {@code ProblemDetail} answer. A code is never renamed; consumers (catapult-web) map codes to
 * their own texts. Each constant carries its usual HTTP status.
 */
public enum ErrorCode {
    // generic, used when nothing more specific is thrown
    BAD_REQUEST(400),
    INVALID_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    CONFLICT(409),
    GONE(410),
    PAYLOAD_TOO_LARGE(413),
    UNSUPPORTED_MEDIA_TYPE(415),
    TOO_MANY_REQUESTS(429),
    INTERNAL_ERROR(500),
    BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503),

    FEATURE_DISABLED(403),

    // a legacy public route that was cut: its successor is in the Link header
    ROUTE_GONE(410),

    // custom bindings (the codes custom binding errors always had)
    TWITCH_UNAVAILABLE(409),
    BOT_DISABLED(409),
    TWITCH_FAILED(502),
    NAME_TAKEN(409),
    LIMIT_REACHED(409),
    INVALID(400),

    TWITCHAT_INVALID_PAYLOAD(400),
    TWITCHAT_WIDGET_DISABLED(409),

    MINECRAFT_DEVICE_CODE_REFUSED(502),
    MINECRAFT_DEVICE_CODE_EXPIRED(400),
    MINECRAFT_ACCOUNT_IN_USE(409);

    /** Name of the problem extension carrying the code. */
    public static final String PROPERTY = "code";

    private final String code;
    private final int status;

    ErrorCode(int status) {
        this.code = name().toLowerCase(Locale.ROOT);
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static Optional<ErrorCode> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst();
    }

    /** The generic constant for a status; other 4xx give BAD_REQUEST, anything else INTERNAL_ERROR. */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 410 -> GONE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> TOO_MANY_REQUESTS;
            case 502 -> BAD_GATEWAY;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 400 && status < 500 ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
