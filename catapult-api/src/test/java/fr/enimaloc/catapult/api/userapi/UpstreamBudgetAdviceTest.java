package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUnavailableException;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** A spent upstream budget becomes a 429 telling the caller when to come back. */
class UpstreamBudgetAdviceTest {

    private final UpstreamBudgetAdvice advice = new UpstreamBudgetAdvice();

    @Test
    void mapsTo429_withRetryAfterRoundedUpToSeconds() {
        ResponseEntity<ProblemDetail> response = advice.tooManyUpstreamCalls(
                new UpstreamBudgetExceededException(Duration.ofMillis(24_200)));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("25");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(429);
        assertThat(response.getBody().getProperties()).containsEntry("code", "too_many_requests");
    }

    @Test
    void aFailingSource_mapsTo503_withRetryAfter_notA404() {
        ResponseEntity<ProblemDetail> response = advice.sourceUnavailable(
                new EnrichmentUnavailableException(Duration.ofSeconds(42)));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(response.getBody().getProperties()).containsEntry("code", "service_unavailable");
    }

    @Test
    void retryAfter_isAtLeastOneSecond() {
        ResponseEntity<ProblemDetail> response = advice.tooManyUpstreamCalls(
                new UpstreamBudgetExceededException(Duration.ZERO));

        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
