package fr.enimaloc.catapult.service.enrichment;

import java.time.Duration;

/** The caller's upstream budget is spent and the answer was not cached. */
public class UpstreamBudgetExceededException extends RuntimeException {

    private final Duration retryAfter;

    public UpstreamBudgetExceededException(Duration retryAfter) {
        super("Upstream call budget exceeded, retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
