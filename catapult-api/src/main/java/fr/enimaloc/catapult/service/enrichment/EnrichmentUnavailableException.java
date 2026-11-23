package fr.enimaloc.catapult.service.enrichment;

import java.time.Duration;

/**
 * A source that backs off failed (or is being left alone after failing) and nothing is known to serve in its place:
 * not "the game is absent". Nothing is cached; the lookup may be asked again after {@link #retryAfter()}.
 */
public class EnrichmentUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public EnrichmentUnavailableException(Duration retryAfter) {
        super("Upstream unavailable, retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
