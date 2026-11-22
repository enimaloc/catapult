package fr.enimaloc.catapult.service.enrichment.xboxstats;

/** Xbox Live refuses the member's session (401/403) for this call: the rights do not cover it. */
public class XboxStatsForbiddenException extends RuntimeException {

    public XboxStatsForbiddenException(String message) {
        super(message);
    }
}
