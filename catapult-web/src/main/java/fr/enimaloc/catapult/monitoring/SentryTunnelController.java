package fr.enimaloc.catapult.monitoring;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * Relays the browser's Sentry reports (monitoring.js sets it as the SDK's tunnel): sent to
 * catapult-web itself, they get past the blockers that drop requests to Sentry, so a bug's replay
 * and trail still arrive. Only envelopes for catapult-web's own DSN go on, to its own project:
 * anything else would make this an open relay. The visitor's IP stays here.
 * <p>
 * Anyone may post here, so it is bounded: an envelope is read up to {@link #MAX_ENVELOPE_BYTES}
 * and no further, and at most {@link #MAX_IN_FLIGHT} wait on Sentry at once (the SDK backs off from
 * the 429 the others get), each for a few seconds only.
 */
@Slf4j
@RestController
public class SentryTunnelController {
    static final String PATH = "/monitoring/tunnel";
    /** Well above a replay segment's few hundred kilobytes. */
    static final int MAX_ENVELOPE_BYTES = 5 * 1024 * 1024;
    static final int MAX_IN_FLIGHT = 8;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI dsn;
    private final URI ingest;
    private final HttpClient http;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    @Autowired
    public SentryTunnelController(@Value("${sentry.dsn:}") String dsn) {
        this(dsn, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    SentryTunnelController(String dsn, HttpClient http) {
        this.dsn = dsn == null || dsn.isBlank() ? null : URI.create(dsn);
        this.ingest = this.dsn == null ? null : ingest(this.dsn);
        this.http = http;
    }

    /** {@code https://key@host/path/42} sends to {@code https://host/path/api/42/envelope/}. */
    static URI ingest(URI dsn) {
        String path = dsn.getPath();
        int project = path.lastIndexOf('/');
        return URI.create(dsn.getScheme() + "://" + dsn.getHost() + (dsn.getPort() < 0 ? "" : ":" + dsn.getPort())
                + path.substring(0, project) + "/api" + path.substring(project) + "/envelope/");
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> relay(HttpServletRequest request) throws IOException, InterruptedException {
        if (ingest == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.getContentLengthLong() > MAX_ENVELOPE_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        byte[] envelope = request.getInputStream().readNBytes(MAX_ENVELOPE_BYTES + 1);
        return relay(envelope);
    }

    ResponseEntity<Void> relay(byte[] envelope) throws InterruptedException {
        if (ingest == null) {
            return ResponseEntity.notFound().build();
        }
        if (envelope.length > MAX_ENVELOPE_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        if (!ownDsn(envelope)) {
            return ResponseEntity.badRequest().build();
        }
        if (!inFlight.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(ingest)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-sentry-envelope")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(envelope))
                    .build(), HttpResponse.BodyHandlers.discarding());
            return ResponseEntity.status(response.statusCode()).build();
        } catch (IOException e) {
            log.warn("Sentry unreachable, a browser report is lost: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } finally {
            inFlight.release();
        }
    }

    /** Whether the envelope's header names catapult-web's DSN: same host, same project. */
    private boolean ownDsn(byte[] envelope) {
        int end = 0;
        while (end < envelope.length && envelope[end] != '\n') end++;
        try {
            JsonNode header = JSON.readTree(new String(envelope, 0, end, StandardCharsets.UTF_8));
            URI claimed = URI.create(header.path("dsn").asString(""));
            return dsn.getHost().equalsIgnoreCase(claimed.getHost()) && dsn.getPath().equals(claimed.getPath());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
