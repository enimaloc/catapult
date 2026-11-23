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

    /**
     * A view of {@code budget} that asks it at most once: after the first answer, every later call repeats that
     * answer without asking again, so a source that makes several upstream calls per fetch is charged a single one.
     */
    static FetchBudget once(FetchBudget budget) {
        return new FetchBudget() {
            private Boolean answer;

            @Override
            public synchronized boolean tryAcquire() {
                if (answer == null) {
                    answer = budget.tryAcquire();
                }
                return answer;
            }

            @Override
            public Duration retryAfter() {
                return budget.retryAfter();
            }
        };
    }
}
