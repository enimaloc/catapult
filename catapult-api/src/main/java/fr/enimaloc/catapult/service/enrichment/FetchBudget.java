package fr.enimaloc.catapult.service.enrichment;

/** Permission to make one upstream call, asked right before it happens. */
@FunctionalInterface
public interface FetchBudget {

    /** Never refuses. */
    FetchBudget UNLIMITED = () -> true;

    /** Takes one call from the budget; false when none is left. */
    boolean tryAcquire();
}
