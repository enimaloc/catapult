package fr.enimaloc.catapult.service.enrichment;

import java.time.Duration;

/** Permission to make one upstream call, asked right before it happens. */
@FunctionalInterface
public interface FetchBudget {

    /** Never refuses. */
    FetchBudget UNLIMITED = () -> true;

    /** Takes one call from the budget; false when none is left. */
    boolean tryAcquire();

    /** How long until a refused {@link #tryAcquire()} may succeed; a conservative minute unless the budget knows. */
    default Duration retryAfter() {
        return Duration.ofMinutes(1);
    }
}
