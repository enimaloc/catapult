package fr.enimaloc.catapult.service.http;

import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

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
