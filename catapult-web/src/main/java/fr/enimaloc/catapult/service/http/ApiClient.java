package fr.enimaloc.catapult.service.http;

import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Calls catapult-api on behalf of the current browser session, authenticated with the JWT
 * stored in that session (it never reaches the browser). A failed call is logged and
 * reported as {@code null} rather than thrown, so a page still renders while the API is down.
 */
@Slf4j
@Component
public class ApiClient {
    public static final String SESSION_JWT_KEY = "jwt";

    /** What nginx answers while catapult-api is still starting: expected, not worth a warning. */
    static final String NOT_STARTED_MESSAGE = "502 Bad Gateway: \"<html><EOL><EOL><head><title>502 Bad Gateway</title></head><EOL><EOL><body><EOL><EOL><center><h1>502 Bad Gateway</h1></center><EOL><EOL><hr><center>openresty</center><EOL><EOL></body><EOL><EOL></html><EOL><EOL>\"";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String AUTH_VALIDATE_PATH = "/api/auth/validate";

    private final RestClient client;

    public ApiClient(@Value("${catapult.backend-url}") String apiUrl, RestClient.Builder builder) {
        this.client = builder
                .baseUrl(apiUrl)
                .requestInterceptor((request, body, execution) -> {
                    String token = currentJwt();
                    if (token != null) request.getHeaders().setBearerAuth(token);
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (request, response) -> {
                    if (response.getStatusCode() != HttpStatus.UNAUTHORIZED
                            && !request.getURI().getPath().equals(AUTH_VALIDATE_PATH)) {
                        log.warn("API returned {} for {}", response.getStatusCode(), request.getURI());
                    }
                })
                .build();
    }

    /** GET {@code path}, its body read as {@code type}; {@code null} when the call fails. */
    public <T> T get(String path, Class<T> type, Object... uriVars) {
        return exchange(HttpMethod.GET, path, type, uriVars);
    }

    /** GET {@code path}, its body read as the generic {@code type}; {@code null} when the call fails. */
    public <T> T get(String path, ParameterizedTypeReference<T> type, Object... uriVars) {
        try {
            return client.get().uri(path, uriVars).retrieve().body(type);
        } catch (Exception e) {
            logFailure(HttpMethod.GET, path, e);
            return null;
        }
    }

    /** A call's HTTP status and its body read as {@code type} (null when empty); status 502 when the call itself failed. */
    public record Result<T>(int status, T body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * {@code method} {@code body} (none when null) to {@code path}, keeping the status and body
     * of a 4xx answer instead of dropping them, for callers that relay validation errors.
     */
    public <T> Result<T> call(HttpMethod method, String path, Object body, Class<T> type, Object... uriVars) {
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(path, uriVars);
            if (body != null) spec.body(body);
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                try {
                    return new Result<>(status, response.bodyTo(type));
                } catch (Exception e) {
                    // The answer's body isn't a {@code type} (e.g. a ProblemDetail): the status still counts.
                    return new Result<T>(status, null);
                }
            });
        } catch (Exception e) {
            logFailure(method, path, e);
            return new Result<>(502, null);
        }
    }

    /** POST {@code path} without a body, the response read as {@code type}; {@code null} on failure. */
    public <T> T post(String path, Class<T> type, Object... uriVars) {
        return exchange(HttpMethod.POST, path, type, uriVars);
    }

    /** POST {@code body} (none when {@code null}) to {@code path}, ignoring the response. */
    public void postVoid(String path, Object body, Object... uriVars) {
        send(HttpMethod.POST, path, body, uriVars);
    }

    /** DELETE {@code path}, ignoring the response. */
    public void delete(String path, Object... uriVars) {
        send(HttpMethod.DELETE, path, null, uriVars);
    }

    private <T> T exchange(HttpMethod method, String path, Class<T> type, Object... uriVars) {
        try {
            return client.method(method).uri(path, uriVars).retrieve().body(type);
        } catch (Exception e) {
            logFailure(method, path, e);
            return null;
        }
    }

    private void send(HttpMethod method, String path, Object body, Object... uriVars) {
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(path, uriVars);
            if (body != null) spec.body(body);
            spec.retrieve().toBodilessEntity();
        } catch (Exception e) {
            logFailure(method, path, e);
        }
    }

    private static void logFailure(HttpMethod method, String path, Exception e) {
        if (!Objects.equals(e.getMessage(), NOT_STARTED_MESSAGE)) {
            log.warn("{} {} failed: {}", method, path, e.getMessage());
        }
    }

    /** Whether the calling request's session is logged in (holds a catapult-api JWT). */
    public static boolean hasSession() {
        return currentJwt() != null;
    }

    /** The subject (account id) of the calling session's JWT; null without one. */
    public static UUID currentSubject() {
        Map<?, ?> claims = currentClaims();
        if (claims == null) return null;
        try {
            return UUID.fromString(String.valueOf(claims.get("sub")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The roles (e.g. ROLE_ADMIN) of the calling session's JWT; empty without one. */
    public static List<String> currentRoles() {
        Map<?, ?> claims = currentClaims();
        if (claims == null || !(claims.get("roles") instanceof List<?> roles)) return List.of();
        return roles.stream().map(String::valueOf).toList();
    }

    /** The calling session's JWT claims; null without one. Read, not verified: the session holds it server-side. */
    private static Map<?, ?> currentClaims() {
        String jwt = currentJwt();
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            return JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String currentJwt() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        try {
            HttpSession session = attributes.getRequest().getSession(false);
            return session != null ? (String) session.getAttribute(SESSION_JWT_KEY) : null;
        } catch (IllegalStateException e) {
            // The request is no longer active (e.g. read from an async thread).
            return null;
        }
    }
}
