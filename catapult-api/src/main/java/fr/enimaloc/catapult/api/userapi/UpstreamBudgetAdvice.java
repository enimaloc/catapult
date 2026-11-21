package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a spent per-IP upstream budget into {@code 429} with a {@code Retry-After} in seconds. Ordered first: the
 * catch-all {@code ProblemAdvice} (unordered too) would otherwise answer it as a 500.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "fr.enimaloc.catapult.api.userapi")
public class UpstreamBudgetAdvice {

    @ExceptionHandler(UpstreamBudgetExceededException.class)
    public ResponseEntity<Void> tooManyUpstreamCalls(UpstreamBudgetExceededException e) {
        long seconds = Math.max(1, (e.retryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .build();
    }
}
