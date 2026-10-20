package fr.enimaloc.catapult.service.http;

import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Calls catapult-api on behalf of the current browser session, authenticated with the JWT
 * stored in that session (it never reaches the browser). A failed read is logged and reported
 * as {@code null} rather than thrown, so a page still renders while the API is down; an error
 * answer (4xx/5xx) is a failure too, its body never read as the expected type. An action
 * answers catapult-api's status instead (502 when unreachable), for the browser to be told.
 */
@Slf4j
@Component
public class ApiClient {
    public static final String SESSION_JWT_KEY = "jwt";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Marks a call that must go out without the session's bearer; the interceptor removes it. */
    private static final String ANONYMOUS_HEADER = "X-Catapult-Anonymous";
    private static final String AUTH_VALIDATE_PATH = "/api/auth/validate";

    private final RestClient client;

    public ApiClient(@Value("${catapult.backend-url}") String apiUrl, RestClient.Builder builder) {
        this.client = builder
                .baseUrl(apiUrl)
                .requestInterceptor((request, body, execution) -> {
                    boolean anonymous = request.getHeaders().remove(ANONYMOUS_HEADER) != null;
                    String token = anonymous ? null : currentJwt();
                    if (token != null) request.getHeaders().setBearerAuth(token);
                    return execution.execute(request, body);
                })
                // An error answer is never read as the expected body: logged here, then thrown to the
                // caller's catch, which reports it as no answer.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    logError(response.getStatusCode(), request.getURI().getPath());
                    throw new ApiErrorException(response.getStatusCode());
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

    /**
     * GET {@code path} in {@code locale}'s language (sent as {@code Accept-Language}, which catapult-api
     * answers its own messages by), its body read as {@code type}; {@code null} when the call fails.
     */
    public <T> T getLocalized(String path, Class<T> type, Locale locale, Object... uriVars) {
        try {
            return client.get().uri(path, uriVars)
                    .header(HttpHeaders.ACCEPT_LANGUAGE, locale.toLanguageTag()).retrieve().body(type);
        } catch (Exception e) {
            logFailure(HttpMethod.GET, path, e);
            return null;
        }
    }

    /**
     * GET {@code path} without the session's bearer (for public content: an expired JWT would make
     * catapult-api refuse it), keeping the status and the body read as {@code type}; 502 when unreachable.
     */
    public <T> Result<T> getAnonymous(String path, Class<T> type, Object... uriVars) {
        try {
            return client.get().uri(path, uriVars).header(ANONYMOUS_HEADER, "1").exchange((request, response) -> {
                int status = response.getStatusCode().value();
                try {
                    return new Result<>(status, status == 200 ? response.bodyTo(type) : null);
                } catch (Exception e) {
                    logFailure(HttpMethod.GET, path, e);
                    return new Result<T>(status, null);
                }
            });
        } catch (Exception e) {
            logFailure(HttpMethod.GET, path, e);
            return new Result<>(502, null);
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

    /** POST {@code body} (none when {@code null}) to {@code path}: catapult-api's status, its body ignored; 502 when unreachable. */
    public int postVoid(String path, Object body, Object... uriVars) {
        return send(HttpMethod.POST, path, body, uriVars);
    }

    /** DELETE {@code path}: catapult-api's status, its body ignored; 502 when unreachable. */
    public int delete(String path, Object... uriVars) {
        return send(HttpMethod.DELETE, path, null, uriVars);
    }

    private <T> T exchange(HttpMethod method, String path, Class<T> type, Object... uriVars) {
        try {
            return client.method(method).uri(path, uriVars).retrieve().body(type);
        } catch (Exception e) {
            logFailure(method, path, e);
            return null;
        }
    }

    private int send(HttpMethod method, String path, Object body, Object... uriVars) {
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(path, uriVars);
            if (body != null) spec.body(body);
            return spec.exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.isError()) logError(status, request.getURI().getPath());
                return status.value();
            });
        } catch (Exception e) {
            logFailure(method, path, e);
            return HttpStatus.BAD_GATEWAY.value();
        }
    }

    /**
     * An error answer, worth a warning unless it only says the session's JWT is no longer valid, or
     * is a 502 while catapult-api is still starting (expected).
     */
    private static void logError(HttpStatusCode status, String path) {
        if (status.value() == HttpStatus.BAD_GATEWAY.value()) {
            log.debug("API returned {} for {}", status, path);
        } else if (status.value() != HttpStatus.UNAUTHORIZED.value() && !path.equals(AUTH_VALIDATE_PATH)) {
            log.warn("API returned {} for {}", status, path);
        }
    }

    private static void logFailure(HttpMethod method, String path, Exception e) {
        // An error answer was logged when it came.
        if (!(e instanceof ApiErrorException)) {
            log.warn("{} {} failed: {}", method, path, e.getMessage());
        }
    }

    /** catapult-api answered {@code status}, an error: what a read reports as no answer. */
    public static final class ApiErrorException extends RuntimeException {
        private final int status;

        ApiErrorException(HttpStatusCode status) {
            super("catapult-api answered " + status.value(), null, false, false);
            this.status = status.value();
        }

        public int status() {
            return status;
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

    /** The Twitch login ({@code username} claim) of the calling session's JWT; null without one. */
    public static String currentLogin() {
        Map<?, ?> claims = currentClaims();
        return claims != null && claims.get("username") instanceof String login && !login.isBlank() ? login : null;
    }

    /** The roles (e.g. ROLE_ADMIN) of the calling session's JWT; empty without one. */
    public static List<String> currentRoles() {
        Map<?, ?> claims = currentClaims();
        if (claims == null || !(claims.get("roles") instanceof List<?> roles)) return List.of();
        return roles.stream().map(String::valueOf).toList();
    }

    /** When {@code jwt} expires (its {@code exp} claim); null when it tells none, or is not a JWT (the mock's). */
    public static Instant expiresAt(String jwt) {
        Map<?, ?> claims = claims(jwt);
        return claims != null && claims.get("exp") instanceof Number exp ? Instant.ofEpochSecond(exp.longValue()) : null;
    }

    /** The calling session's JWT claims; null without one. Read, not verified: the session holds it server-side. */
    private static Map<?, ?> currentClaims() {
        return claims(currentJwt());
    }

    private static Map<?, ?> claims(String jwt) {
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
