package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.RawExchange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Logs every outbound HTTP call performed through a Spring {@link org.springframework.web.client.RestClient}
 * at {@code TRACE}. Captures method + URI + request body, then the status +
 * response body. Reading the body consumes it, so the caller gets a response
 * replaying the bytes read: a client with its own unbuffered request factory
 * (IanaWellKnownRegistry, SchemaStoreRegistry) would otherwise find its stream
 * closed whenever TRACE is on.
 *
 * <p>Bodies are truncated past {@link #MAX_BODY_CHARS} so a single large
 * response (Steam app list, IGDB game JSON) can't swamp the log line. The
 * suffix {@code …(+N bytes)} makes the cut visible.</p>
 *
 * <p>{@code Authorization} / {@code X-API-KEY} / {@code X-Riot-Token} / {@code API-Key} (Hypixel) values are never echoed — only
 * their presence is reported. Neither are the values of the {@code key},
 * {@code api_key}, {@code apikey}, {@code token}, {@code y}, {@code client_secret}, {@code access_token} and
 * {@code refresh_token} query parameters (any case),
 * masked in the logged URI.</p>
 *
 * <p>The same exchange also feeds the per-member debug mode: it joins the active
 * {@link DebugContext#capture capture} if any, else it is recorded for the current
 * subject when that member is in debug mode. Only then, or at TRACE, is the
 * response body read.</p>
 */
@Slf4j
@Component
public class HttpTraceLoggingInterceptor implements ClientHttpRequestInterceptor {

    static final int MAX_BODY_CHARS = 4096;
    /** A credential query parameter: its name and {@code =} (group 1), then its value up to the next one. */
    private static final Pattern SECRET_QUERY_PARAM =
            Pattern.compile("([?&](?:key|api_key|apikey|token|y|client_secret|access_token|refresh_token)=)[^&#]*",
                    Pattern.CASE_INSENSITIVE);

    /** A credential field of a form-encoded request body (an OAuth token request): its name and {@code =}. */
    private static final Pattern SECRET_FORM_FIELD =
            Pattern.compile("((?:^|&)(?:client_secret|access_token|refresh_token|password)=)[^&]*",
                    Pattern.CASE_INSENSITIVE);

    /** A Riot API key ({@code RGAPI-<uuid>}) quoted in a body: never logged. */
    private static final Pattern RIOT_KEY =
            Pattern.compile("(?i)RGAPI-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** An OAuth token answer, from any provider: its tokens are credentials (they never reach the log). */
    private static final Pattern ACCESS_TOKEN_FIELD =
            Pattern.compile("(\"(?:access_token|refresh_token|id_token)\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\")");

    private final DebugMemberRegistry registry;
    private final DebugExchangeRecorder recorder;

    /** Trace logging only, no debug recording (clients built by hand, tests). */
    public HttpTraceLoggingInterceptor() {
        this(null, null);
    }

    @Autowired
    public HttpTraceLoggingInterceptor(DebugMemberRegistry registry, DebugExchangeRecorder recorder) {
        this.registry = registry;
        this.recorder = recorder;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        long started = System.nanoTime();
        Instant at = Instant.now();
        boolean trace = log.isTraceEnabled();
        Debug debug = debugTarget();
        if (trace) {
            log.trace("HTTP req -> {} {} auth={} body={}",
                    request.getMethod(),
                    masked(request.getURI()),
                    redactAuth(request),
                    truncate(loggedRequest(body)));
        }
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            if (trace) {
                log.trace("HTTP req x  {} {} after {}ms: {}",
                        request.getMethod(), masked(request.getURI()), elapsedMs, ex.toString());
            }
            if (debug != null) {
                debug.emit(raw(at, request, body, null, null, ex.toString(), elapsedMs));
            }
            throw ex;
        }
        if (!trace && debug == null) {
            return response;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        byte[] respBody;
        try (var in = response.getBody()) {
            respBody = in.readAllBytes();
        } catch (Exception e) {
            // Unreadable to the caller as well: hand it the response as it came.
            if (trace) {
                log.trace("HTTP res <- {} {} {} in {}ms body=<unreadable body: {}>",
                        response.getStatusCode().value(), request.getMethod(), masked(request.getURI()),
                        elapsedMs, e.getMessage());
            }
            if (debug != null) {
                debug.emit(raw(at, request, body, response, null, "unreadable response body: " + e, elapsedMs));
            }
            return response;
        }
        if (trace) {
            log.trace("HTTP res <- {} {} {} in {}ms body={}",
                    response.getStatusCode().value(),
                    request.getMethod(),
                    masked(request.getURI()),
                    elapsedMs,
                    truncate(logged(respBody)));
        }
        if (debug != null) {
            debug.emit(raw(at, request, body, response, respBody, null, elapsedMs));
        }
        return new ReplayedResponse(response, respBody);
    }

    /**
     * Where this call's exchange goes when it must be kept: the active capture, else the recording of the current
     * subject when that member is in debug mode; null otherwise (the common case: one map lookup at most).
     */
    private Debug debugTarget() {
        try {
            if (DebugContext.capturing()) {
                return raw -> DebugContext.record(raw);
            }
            if (registry == null || recorder == null) {
                return null;
            }
            UUID subject = DebugContext.subject().orElse(null);
            if (subject == null || registry.modeOf(subject).isEmpty()) {
                return null;
            }
            return raw -> recorder.record(subject, Direction.OUT, raw, null, false);
        } catch (RuntimeException e) {
            log.warn("Could not tell whether to record a debug exchange: {}", e.toString());
            return null;
        }
    }

    /** Hands an exchange over to the debug recording; whatever goes wrong there never reaches the call. */
    @FunctionalInterface
    private interface Debug {
        void accept(RawExchange raw);

        default void emit(Supplier<RawExchange> raw) {
            try {
                accept(raw.get());
            } catch (RuntimeException e) {
                log.warn("Could not record a debug exchange: {}", e.toString());
            }
        }
    }

    private static Supplier<RawExchange> raw(Instant at, HttpRequest request, byte[] body, ClientHttpResponse response,
                                             byte[] respBody, String error, long elapsedMs) {
        return () -> {
            Integer status = null;
            HttpHeaders responseHeaders = null;
            if (response != null) {
                try {
                    status = response.getStatusCode().value();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                responseHeaders = HttpHeaders.copyOf(response.getHeaders());
            }
            return new RawExchange(at, request.getMethod().name(), request.getURI(),
                    HttpHeaders.copyOf(request.getHeaders()), body, status, responseHeaders, respBody, error,
                    elapsedMs);
        };
    }

    /** {@code response} with its body replaced by the bytes already read from it. */
    private record ReplayedResponse(ClientHttpResponse response, byte[] body) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            response.close();
        }
    }

    /** {@code uri} with the value of every credential query parameter replaced by {@code ***}. */
    static String masked(URI uri) {
        return SECRET_QUERY_PARAM.matcher(uri.toString()).replaceAll("$1***");
    }

    /** The request body as it may be logged: the credentials of a form-encoded token request are masked. */
    private static byte[] loggedRequest(byte[] body) {
        if (body == null || body.length == 0) {
            return body;
        }
        String masked = SECRET_FORM_FIELD.matcher(new String(body, StandardCharsets.UTF_8)).replaceAll("$1***");
        return masked.getBytes(StandardCharsets.UTF_8);
    }

    /** The response body as it may be logged: the tokens of an OAuth token answer are masked. */
    private static byte[] logged(byte[] respBody) {
        if (respBody == null || respBody.length == 0) {
            return respBody;
        }
        String masked = ACCESS_TOKEN_FIELD.matcher(new String(respBody, StandardCharsets.UTF_8)).replaceAll("$1***$2");
        masked = RIOT_KEY.matcher(masked).replaceAll("***");
        return masked.getBytes(StandardCharsets.UTF_8);
    }

    private static String redactAuth(HttpRequest request) {
        var headers = request.getHeaders();
        boolean hasAuth = headers.getFirst("Authorization") != null;
        boolean hasApiKey = headers.getFirst("X-API-KEY") != null
                || headers.getFirst("Client-Id") != null
                || headers.getFirst("X-Riot-Token") != null
                || headers.getFirst("API-Key") != null;
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
