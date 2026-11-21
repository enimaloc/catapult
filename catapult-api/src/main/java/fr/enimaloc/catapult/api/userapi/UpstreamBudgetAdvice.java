package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a spent per-IP upstream budget into a {@code 429} problem ({@code too_many_requests}) with a
 * {@code Retry-After} in seconds. Ordered first: the
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
}
