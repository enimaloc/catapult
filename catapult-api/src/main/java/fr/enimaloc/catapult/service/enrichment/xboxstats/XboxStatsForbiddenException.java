package fr.enimaloc.catapult.service.enrichment.xboxstats;

/**
 * Xbox Live refuses the member's session for this call: 403 (the rights do not cover it) or 401 (which can be a
 * token that just expired, so it is not a lasting refusal).
 */
public class XboxStatsForbiddenException extends RuntimeException {

    private final int status;

    public XboxStatsForbiddenException(String message) {
        this(message, 403);
    }

    public XboxStatsForbiddenException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** The HTTP status Xbox answered, 401 or 403. */
    public int status() {
        return status;
    }
}
