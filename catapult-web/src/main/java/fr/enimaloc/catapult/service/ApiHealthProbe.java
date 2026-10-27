package fr.enimaloc.catapult.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Whether catapult-api answers its health, asked anonymously with a short timeout and kept a few seconds:
 * one probe at a time, whatever the number of visitors asking, so a flood of callers never reaches catapult-api.
 */
@Slf4j
@Service
public class ApiHealthProbe {
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final Duration TTL = Duration.ofSeconds(5);
    private static final String HEALTH_PATH = "/api/internal/health";

    private final HttpClient http;
    private final URI healthUri;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean up;
    private volatile Instant until = Instant.MIN;

    @Autowired
    public ApiHealthProbe(@Value("${catapult.backend-url}") String apiUrl) {
        this(apiUrl, Clock.systemUTC());
    }

    ApiHealthProbe(String apiUrl, Clock clock) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
        this.healthUri = URI.create(apiUrl.replaceAll("/+$", "") + HEALTH_PATH);
        this.clock = clock;
    }

    /** Whether catapult-api answered its health with a 200 in the last few seconds (probed now when none is fresh). */
    public boolean up() {
        if (clock.instant().isBefore(until)) return up;
        lock.lock();
        try {
            // A caller that waited on the probe in flight takes its answer instead of probing again.
            if (clock.instant().isBefore(until)) return up;
            boolean answer = probe();
            up = answer;
            until = clock.instant().plus(TTL);
            return answer;
        } finally {
            lock.unlock();
        }
    }

    private boolean probe() {
        CompletableFuture<HttpResponse<Void>> call = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri).GET().build();
            call = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            // The whole exchange is bounded, the body read included (a request timeout stops at the headers).
            return call.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode() == 200;
        } catch (TimeoutException e) {
            call.cancel(true);
            log.debug("catapult-api health probe timed out");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("catapult-api health probe failed: {}", e.toString());
            return false;
        }
    }
}
