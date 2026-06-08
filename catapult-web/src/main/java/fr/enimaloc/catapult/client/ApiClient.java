package fr.enimaloc.catapult.client;

import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * HTTP client for catapult-api. Automatically attaches the JWT Bearer token
 * stored in the current session (key: "jwt") to every request.
 */
@Slf4j
@Component
public class ApiClient {

    public static final String SESSION_JWT_KEY = "jwt";

    private final String apiUrl;
    private final RestClient restClient;

    public ApiClient(@Value("${catapult.api.url}") String apiUrl) {
        this.apiUrl = apiUrl;
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
        try {
            return restClient.get()
                    .uri(path, uriVars)
                    .retrieve()
                    .body(responseType);
        } catch (Exception e) {
            log.warn("GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    public <T> T get(String path, ParameterizedTypeReference<T> responseType, Object... uriVars) {
        try {
            return restClient.get()
                    .uri(path, uriVars)
                    .retrieve()
                    .body(responseType);
        } catch (Exception e) {
            log.warn("GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    public <T> T post(String path, Object body, Class<T> responseType, Object... uriVars) {
        try {
            return restClient.post()
                    .uri(path, uriVars)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (Exception e) {
            log.warn("POST {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    public void post(String path, Object body, Object... uriVars) {
        try {
            if (body != null) {
                restClient.post()
                        .uri(path, uriVars)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } else {
                restClient.post()
                        .uri(path, uriVars)
                        .retrieve()
                        .toBodilessEntity();
            }
        } catch (Exception e) {
            log.warn("POST {} failed: {}", path, e.getMessage());
        }
    }

    public void streamSse(String path, SseEmitter emitter, Object... uriVars) {
        String jwt = currentJwt(); // capture on the request thread before handing off
        Thread.ofVirtual().start(() -> {
            try {
                RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader(HttpHeaders.AUTHORIZATION, jwt != null ? "Bearer " + jwt : "")
                        .build()
                        .get()
                        .uri(path, uriVars)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange((req, res) -> {
                            if (!res.getStatusCode().is2xxSuccessful()) {
                                emitter.completeWithError(new IOException("SSE upstream " + res.getStatusCode()));
                                return null;
                            }
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.getBody()))) {
                                String dataLine = null;
                                String nameLine = null;
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("event:")) {
                                        nameLine = line.substring(6).trim();
                                    } else if (line.startsWith("data:")) {
                                        dataLine = line.substring(5).trim();
                                    } else if (line.isEmpty() && dataLine != null) {
                                        SseEmitter.SseEventBuilder event = SseEmitter.event().data(dataLine);
                                        if (nameLine != null) event.name(nameLine);
                                        try {
                                            emitter.send(event);
                                        } catch (IOException ignored) {
                                            return null; // browser disconnected
                                        }
                                        dataLine = null;
                                        nameLine = null;
                                    }
                                }
                            }
                            emitter.complete();
                            return null;
                        });
            } catch (Exception e) {
                log.warn("SSE stream {} ended: {}", path, e.getMessage());
                try { emitter.completeWithError(e); } catch (IllegalStateException ignored) {}
            }
        });
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
