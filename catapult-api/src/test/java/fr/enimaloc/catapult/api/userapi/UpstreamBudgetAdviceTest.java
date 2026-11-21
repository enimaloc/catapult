package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** A spent upstream budget becomes a 429 telling the caller when to come back. */
class UpstreamBudgetAdviceTest {

    private final UpstreamBudgetAdvice advice = new UpstreamBudgetAdvice();

    @Test
    void mapsTo429_withRetryAfterRoundedUpToSeconds() {
        ResponseEntity<Void> response = advice.tooManyUpstreamCalls(
                new UpstreamBudgetExceededException(Duration.ofMillis(24_200)));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("25");
    }

    @Test
    void retryAfter_isAtLeastOneSecond() {
        ResponseEntity<Void> response = advice.tooManyUpstreamCalls(
                new UpstreamBudgetExceededException(Duration.ZERO));

        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
