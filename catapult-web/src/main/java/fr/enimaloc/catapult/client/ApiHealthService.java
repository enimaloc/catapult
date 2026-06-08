package fr.enimaloc.catapult.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Checks catapult-api reachability and caches the result for TTL_MS milliseconds.
 * Uses a plain (no-auth) RestClient so it works even when no session JWT is present.
 */
@Slf4j
@Service
public class ApiHealthService {

    private static final long TTL_MS = 20_000;

    private final RestClient healthClient;
    private volatile boolean available = true;
    private volatile long lastChecked = 0;

    public ApiHealthService(@Value("${catapult.api.url}") String apiUrl) {
        this.healthClient = RestClient.builder().baseUrl(apiUrl).build();
    }

    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastChecked > TTL_MS) {
            lastChecked = now;
            try {
                healthClient.get().uri("/api/health").retrieve().toBodilessEntity();
                if (!available) {
                    log.info("catapult-api is back online");
                }
                available = true;
            } catch (Exception e) {
                if (available) {
                    log.warn("catapult-api is unreachable: {}", e.getMessage());
                }
                available = false;
            }
        }
        return available;
    }
}
