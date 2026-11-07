package fr.enimaloc.catapult.repository.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The optional filters of a member's exchange list. Every predicate is ANDed onto the member's
 * {@code user_id}, so the (user_id, occurred_at DESC) index serves the range and the ordering.
 */
public final class DebugExchangeSpecs {

    /** HTTP status from which an exchange counts as failed; see {@link DebugExchange#isError()}. */
    private static final int FIRST_ERROR_STATUS = 400;

    private DebugExchangeSpecs() {
    }

    /**
     * @param outcome null (any), {@code true} for the exchanges {@link DebugExchange#isError()} calls
     *                errors (error set, an outgoing call without a status, or status >= 400), {@code false}
     *                for the others
     */
    public static Specification<DebugExchange> matching(UUID userId, String service, DebugExchange.Direction direction,
                                                        Boolean outcome, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("userId"), userId));
            if (service != null) {
                all.add(cb.equal(root.get("service"), service));
            }
            if (direction != null) {
                all.add(cb.equal(root.get("direction"), direction));
            }
            if (from != null) {
                all.add(cb.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), from));
            }
            if (to != null) {
                all.add(cb.lessThanOrEqualTo(root.<Instant>get("occurredAt"), to));
            }
            if (outcome != null) {
                // Every disjunct is true or false, never SQL NULL, so that not(error) keeps the successes.
                Predicate error = cb.or(cb.isNotNull(root.get("error")),
                        cb.and(cb.isNull(root.get("status")),
                                cb.notEqual(root.get("direction"), DebugExchange.Direction.IN)),
                        cb.and(cb.isNotNull(root.get("status")),
                                cb.greaterThanOrEqualTo(root.<Integer>get("status"), FIRST_ERROR_STATUS)));
                all.add(outcome ? error : cb.not(error));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }
}
