package fr.enimaloc.catapult.client;

import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP client for catapult-api. Automatically attaches the JWT Bearer token
 * stored in the current session (key: "jwt") to every request.
 */
@Slf4j
@Component
public class ApiClient {

    public static final String SESSION_JWT_KEY = "jwt";

    private final RestClient restClient;

    public ApiClient(@Value("${catapult.api.url}") String apiUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .requestInterceptor((request, body, execution) -> {
                    String token = currentJwt();
                    if (token != null) {
                        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    }
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (req, res) -> {
                    log.warn("catapult-api returned {} for {}", res.getStatusCode(), req.getURI());
                })
                .build();
    }

    public <T> T get(String path, Class<T> responseType, Object... uriVars) {
        return restClient.get()
                .uri(path, uriVars)
                .retrieve()
                .body(responseType);
    }

    public <T> T get(String path, ParameterizedTypeReference<T> responseType, Object... uriVars) {
        return restClient.get()
                .uri(path, uriVars)
                .retrieve()
                .body(responseType);
    }

    public <T> T post(String path, Object body, Class<T> responseType, Object... uriVars) {
        return restClient.post()
                .uri(path, uriVars)
                .body(body)
                .retrieve()
                .body(responseType);
    }

    private static String currentJwt() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpSession session = attrs.getRequest().getSession(false);
            return session != null ? (String) session.getAttribute(SESSION_JWT_KEY) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
