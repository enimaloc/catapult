package fr.enimaloc.catapult.service.degraded;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Reads the active incidents of an Instatus status page ({@code <page>/summary.json}). */
@Slf4j
@Component
public class InstatusClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient client;

    @Autowired
    public InstatusClient(RestClient.Builder builder) {
        this(builder
                .requestFactory(timeouts())
                .build());
    }

    /** Takes the client ready made, so a test can bind it to a mock server. */
    InstatusClient(RestClient client) {
        this.client = client;
    }

    private static JdkClientHttpRequestFactory timeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }

    /**
     * The link of the most recently started active incident; empty when there is none or the page can't
     * be read. Only {@code http(s)} links are kept, as the page is not ours.
     */
    public Optional<String> latestIncidentUrl(String pageUrl) {
        try {
            String base = pageUrl.endsWith("/") ? pageUrl.substring(0, pageUrl.length() - 1) : pageUrl;
            String body = client.get().uri(base + "/summary.json").retrieve().body(String.class);
            JsonNode incidents = JSON.readTree(body).path("activeIncidents");
            String latestUrl = null;
            Instant latestStart = null;
            for (JsonNode incident : incidents) {
                String url = incident.path("url").asString(null);
                Instant started = parseInstant(incident.path("started").asString(null));
                if (url == null || started == null || !isHttp(url)) continue;
                if (latestStart == null || started.isAfter(latestStart)) {
                    latestUrl = url;
                    latestStart = started;
                }
            }
            return Optional.ofNullable(latestUrl);
        } catch (Exception e) {
            log.debug("Instatus incidents of {} not read: {}", pageUrl, e.toString());
            return Optional.empty();
        }
    }

    private static boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static Instant parseInstant(String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
