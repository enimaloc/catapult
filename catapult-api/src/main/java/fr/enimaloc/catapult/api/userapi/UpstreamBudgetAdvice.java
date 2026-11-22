package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.service.enrichment.EnrichmentUnavailableException;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a spent per-IP upstream budget into a {@code 429} problem ({@code too_many_requests}), and a source that is
 * failing into a {@code 503} ({@code service_unavailable}), each with a {@code Retry-After} in seconds. Ordered first: the
 * catch-all {@code ProblemAdvice} (unordered too) would otherwise answer it as a 500.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "fr.enimaloc.catapult.api.userapi")
public class UpstreamBudgetAdvice {

    @ExceptionHandler(UpstreamBudgetExceededException.class)
    public ResponseEntity<ProblemDetail> tooManyUpstreamCalls(UpstreamBudgetExceededException e) {
        return RetryAfterProblems.answer(ErrorCode.TOO_MANY_REQUESTS,
                "Too many upstream calls from this address", e.retryAfter());
    }

    /** A source that failed and is left alone for a while: not "unknown", so a 503 telling when to come back. */
    @ExceptionHandler(EnrichmentUnavailableException.class)
    public ResponseEntity<ProblemDetail> sourceUnavailable(EnrichmentUnavailableException e) {
        return RetryAfterProblems.answer(ErrorCode.SERVICE_UNAVAILABLE,
                "The upstream source is unavailable right now", e.retryAfter());
    }
}
