package fr.enimaloc.catapult.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Logs every outbound HTTP call performed through a Spring {@link org.springframework.web.client.RestClient}
 * at {@code TRACE}. Captures method + URI + request body, then the status +
 * response body — the latter requires a buffering request factory so the body
 * stream is re-readable; this is wired in {@link WebClientConfig}.
 *
 * <p>Bodies are truncated past {@link #MAX_BODY_CHARS} so a single large
 * response (Steam app list, IGDB game JSON) can't swamp the log line. The
 * suffix {@code …(+N bytes)} makes the cut visible.</p>
 *
 * <p>{@code Authorization} / {@code X-API-KEY} values are never echoed — only
 * their presence is reported.</p>
 */
@Slf4j
@Component
public class HttpTraceLoggingInterceptor implements ClientHttpRequestInterceptor {

    static final int MAX_BODY_CHARS = 4096;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        long started = System.nanoTime();
        if (log.isTraceEnabled()) {
            log.trace("HTTP req -> {} {} auth={} body={}",
                    request.getMethod(),
                    request.getURI(),
                    redactAuth(request),
                    truncate(body));
        }
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException ex) {
            if (log.isTraceEnabled()) {
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                log.trace("HTTP req x  {} {} after {}ms: {}",
                        request.getMethod(), request.getURI(), elapsedMs, ex.toString());
            }
            throw ex;
        }
        if (log.isTraceEnabled()) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            byte[] respBody = readBodyQuietly(response);
            log.trace("HTTP res <- {} {} {} in {}ms body={}",
                    response.getStatusCode().value(),
                    request.getMethod(),
                    request.getURI(),
                    elapsedMs,
                    truncate(respBody));
        }
        return response;
    }

    private static byte[] readBodyQuietly(ClientHttpResponse response) {
        try (var in = response.getBody()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return ("<unreadable body: " + e.getMessage() + ">").getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String redactAuth(HttpRequest request) {
        var headers = request.getHeaders();
        boolean hasAuth = headers.getFirst("Authorization") != null;
        boolean hasApiKey = headers.getFirst("X-API-KEY") != null
                || headers.getFirst("Client-Id") != null;
        if (hasAuth && hasApiKey) return "Bearer+ApiKey";
        if (hasAuth) return "Bearer";
        if (hasApiKey) return "ApiKey";
        return "none";
    }

    private static String truncate(byte[] payload) {
        if (payload == null || payload.length == 0) return "<empty>";
        String s = new String(payload, StandardCharsets.UTF_8);
        if (s.length() <= MAX_BODY_CHARS) return s;
        return s.substring(0, MAX_BODY_CHARS) + "…(+" + (s.length() - MAX_BODY_CHARS) + " chars)";
    }
}
