package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
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

    private final ApiService apiService;
    private final Clock clock;
    private volatile Cached cached;

    @Autowired
    public StatusController(ApiService apiService) {
        this(apiService, Clock.systemUTC());
    }

    StatusController(ApiService apiService, Clock clock) {
        this.apiService = apiService;
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
        boolean available = apiService.apiAvailable();
        cached = new Cached(available, now.plus(TTL));
        return available;
    }
}
