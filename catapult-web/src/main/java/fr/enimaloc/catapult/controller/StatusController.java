package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.http.ApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * {@code GET /status}: whether catapult-api answers ({@code {"available": true}}), for a page or a
 * monitor waiting for it to come back. Its health is asked without the session's bearer and the
 * answer kept a few seconds, so visitors polling it don't each reach catapult-api.
 */
@RestController
public class StatusController {
    static final Duration TTL = Duration.ofSeconds(5);

    private record Cached(boolean available, Instant until) {}

    private final ApiClient apiClient;
    private final Clock clock;
    private volatile Cached cached;

    @Autowired
    public StatusController(ApiClient apiClient) {
        this(apiClient, Clock.systemUTC());
    }

    StatusController(ApiClient apiClient, Clock clock) {
        this.apiClient = apiClient;
        this.clock = clock;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("available", available()));
    }

    private boolean available() {
        Instant now = clock.instant();
        Cached current = cached;
        if (current != null && now.isBefore(current.until())) return current.available();
        @SuppressWarnings("rawtypes")
        ApiClient.Result<Map> health = apiClient.getAnonymous("/api/health", Map.class);
        boolean available = health.ok() && health.body() != null && "UP".equals(health.body().get("status"));
        cached = new Cached(available, now.plus(TTL));
        return available;
    }
}
