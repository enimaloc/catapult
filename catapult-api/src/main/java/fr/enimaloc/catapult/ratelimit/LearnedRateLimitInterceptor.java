package fr.enimaloc.catapult.ratelimit;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Keeps every outbound call to a host without a documented limit to what {@link LearnedRateLimiter} learned: waits
 * (or refuses with a local 429) before sending, then tells it the answer. Hosts left to their documented limits pass
 * untouched.
 */
@Component
public class LearnedRateLimitInterceptor implements ClientHttpRequestInterceptor {

    private final LearnedRateLimiter limiter;

    public LearnedRateLimitInterceptor(LearnedRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        RouteKey key = RouteKey.of(request);
        if (!limiter.learns(key.host())) return execution.execute(request, body);
        limiter.acquire(key);
        ClientHttpResponse response = execution.execute(request, body);
        limiter.onResponse(key, response.getStatusCode(), response.getHeaders());
        return response;
    }
}
