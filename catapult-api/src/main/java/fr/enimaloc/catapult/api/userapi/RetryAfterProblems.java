package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.common.error.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

/** A problem answer telling the caller when to come back: {@code Retry-After} in whole seconds, at least one. */
final class RetryAfterProblems {

    private RetryAfterProblems() {}

    /** {@code retryAfter} rounded up to whole seconds, never below one. */
    static long seconds(Duration retryAfter) {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    /** A problem of {@code code}'s status carrying {@code code} and {@code detail}, with its {@code Retry-After}. */
    static ResponseEntity<ProblemDetail> answer(ErrorCode code, String detail, Duration retryAfter) {
        HttpStatusCode status = HttpStatusCode.valueOf(code.status());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(ErrorCode.PROPERTY, code.code());
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds(retryAfter)))
                .body(problem);
    }
}
