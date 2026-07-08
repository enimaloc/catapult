package fr.enimaloc.catapult.client;

import fr.enimaloc.catapult.web.ws.auth.WsAuthContext;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
            var spec = restClient.post().uri(path, uriVars);
            // RestClient.RequestBodySpec#body(null) NPEs on body.getClass(); skip when no payload.
            return (body == null ? spec.retrieve() : spec.body(body).retrieve())
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

    public boolean put(String path, Object body, Object... uriVars) {
        try {
            restClient.put()
                    .uri(path, uriVars)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("PUT {} failed: {}", path, e.getMessage());
            return false;
        }
    }

    public boolean delete(String path, Object... uriVars) {
        try {
            restClient.delete()
                    .uri(path, uriVars)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("DELETE {} failed: {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * Returns a page of notifications for the given user.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param userId the UUID of the user (used for logging)
     * @param page   zero-based page index
     * @param size   page size (caller is responsible for capping)
     * @return the Spring Page serialised as a map, or {@code null} if the upstream call fails
     */
    public java.util.Map<String, Object> notificationList(UUID userId, int page, int size) {
        try {
            return restClient.get()
                    .uri("/api/notifications?page={page}&size={size}", page, size)
                    .retrieve()
                    .body(new ParameterizedTypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("GET /api/notifications failed for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Marks all notifications as read for the given user.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param userId the UUID of the user (used for logging)
     */
    public void notificationMarkAll(UUID userId) {
        try {
            restClient.post()
                    .uri("/api/notifications/read-all")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("POST /api/notifications/read-all failed for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Returns the notification snapshot for the given user.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param userId the UUID of the user whose snapshot is requested (used for logging)
     * @return the snapshot, or {@code null} if the upstream call fails
     */
    public NotificationSnapshotDto notificationSnapshot(UUID userId) {
        try {
            return restClient.get()
                    .uri("/api/notifications/snapshot")
                    .retrieve()
                    .body(NotificationSnapshotDto.class);
        } catch (Exception e) {
            log.warn("GET /api/notifications/snapshot failed for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns a page of notifications for admin review.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param page zero-based page index
     * @param size page size (caller is responsible for capping)
     * @return the Spring Page serialised as a map, or {@code null} if the upstream call fails
     */
    public java.util.Map<String, Object> adminNotificationList(int page, int size) {
        try {
            return restClient.get()
                    .uri("/api/admin/notifications?page={page}&size={size}", page, size)
                    .retrieve()
                    .body(new ParameterizedTypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("GET /api/admin/notifications failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Creates a new notification via the admin API.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param body the notification fields (serialised by Jackson)
     * @return the created notification as a map, or {@code null} if the upstream call fails
     */
    public java.util.Map<String, Object> adminNotificationCreate(Object body) {
        try {
            return restClient.post()
                    .uri("/api/admin/notifications")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("POST /api/admin/notifications failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sends a broadcast event via the admin API.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param params the broadcast parameters (channel, name, data)
     * @return the API response as a map, or {@code null} if the upstream call fails
     */
    public java.util.Map<String, Object> adminBroadcastSend(java.util.Map<String, Object> body) {
        try {
            org.springframework.http.ResponseEntity<Void> resp = restClient.post()
                    .uri("/api/admin/broadcast")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return resp.getStatusCode().is2xxSuccessful() ? java.util.Map.of("accepted", true) : null;
        } catch (Exception e) {
            log.warn("POST /api/admin/broadcast failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the config catalog from catapult-api for the given module.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param module the config module (e.g. "api")
     * @return the list of config entries, or {@code null} if the upstream call fails
     */
    public java.util.List<java.util.Map<String, Object>> adminConfigCatalog(String module) {
        try {
            return restClient.get()
                    .uri("/api/admin/config?module={module}", module)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.warn("GET /api/admin/config failed for module {}: {}", module, e.getMessage());
            return null;
        }
    }

    /**
     * Sets a config value via the admin API.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param key   the config key
     * @param value the new value (serialised as JSON)
     * @return {@code true} on success, {@code false} if the call failed
     */
    public boolean adminConfigSet(String key, Object value) {
        return put("/api/admin/config/{key}", java.util.Map.of("value", value), key);
    }

    /**
     * Resets a config value to its default via the admin API.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param key the config key to reset
     * @return {@code true} on success, {@code false} if the call failed
     */
    public boolean adminConfigReset(String key) {
        return delete("/api/admin/config/{key}", key);
    }

    /**
     * Deletes a notification by its ID via the admin API.
     * The session JWT must be active in {@link WsAuthContext} so the interceptor
     * can attach the correct Bearer token.
     *
     * @param id the notification UUID to delete
     * @return {@code true} on success, {@code false} if the notification was not found or the call failed
     */
    public boolean adminNotificationDelete(UUID id) {
        try {
            restClient.delete()
                    .uri("/api/admin/notifications/{id}", id)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("DELETE /api/admin/notifications/{} failed: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Exchanges a one-time code (received from catapult-api's OAuth2 redirect) for a JWT.
     * This call is server-to-server: the JWT itself never travels through the browser.
     * Returns null if the code is expired, already used, or the exchange request fails.
     */
    public String exchangeCode(String code) {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> body = restClient.post()
                    .uri("/api/auth/exchange?code={code}", code)
                    .retrieve()
                    .body(java.util.Map.class);
            return body != null ? body.get("token") : null;
        } catch (Exception e) {
            log.warn("Code exchange failed: {}", e.getMessage());
            return null;
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

    // ── Minecraft ────────────────────────────────────────────────────────────

    /** Résultat brut d'un appel dont l'appelant doit brancher sur le code HTTP. */
    public record ApiResult(int status, Map<String, Object> body) {}

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    public Map<String, Object> minecraftLinkState() {
        return get("/api/connect/minecraft", MAP_TYPE);
    }

    public ApiResult minecraftEnroll(String name) {
        return exchangeForResult(() -> restClient.post()
                .uri("/api/connect/minecraft")
                .body(Map.of("name", name)));
    }

    public boolean minecraftUnenroll() {
        return delete("/api/connect/minecraft");
    }

    public List<Map<String, Object>> adminMinecraftAccounts() {
        return get("/api/admin/minecraft-accounts", new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    /** Normalise la réponse snake_case de l'api (device_code, user_code…) en camelCase pour le front. */
    public Map<String, Object> adminMinecraftDeviceCodeStart() {
        ApiResult result = exchangeForResult(() -> restClient.post()
                .uri("/api/admin/minecraft-accounts/device-code"));
        if (result.status() != 200) return null;
        Map<String, Object> raw = result.body();
        return Map.of(
                "deviceCode", String.valueOf(raw.getOrDefault("device_code", "")),
                "userCode", String.valueOf(raw.getOrDefault("user_code", "")),
                "verificationUri", String.valueOf(raw.getOrDefault("verification_uri", "")),
                "interval", raw.getOrDefault("interval", 5));
    }

    public ApiResult adminMinecraftCreate(String deviceCode, String label) {
        return exchangeForResult(() -> restClient.post()
                .uri("/api/admin/minecraft-accounts")
                .body(Map.of("deviceCode", deviceCode, "label", label)));
    }

    public boolean adminMinecraftPatch(UUID id, Map<String, Object> body) {
        try {
            return restClient.method(HttpMethod.PATCH)
                    .uri("/api/admin/minecraft-accounts/{id}", id)
                    .body(body)
                    .exchange((req, res) -> res.getStatusCode().is2xxSuccessful());
        } catch (Exception e) {
            log.warn("PATCH /api/admin/minecraft-accounts/{} failed: {}", id, e.getMessage());
            return false;
        }
    }

    public int adminMinecraftDelete(UUID id) {
        try {
            return restClient.delete()
                    .uri("/api/admin/minecraft-accounts/{id}", id)
                    .exchange((req, res) -> res.getStatusCode().value());
        } catch (Exception e) {
            log.warn("DELETE /api/admin/minecraft-accounts/{} failed: {}", id, e.getMessage());
            return 500;
        }
    }

    /**
     * Exécute la requête et retourne statut + body sans lever d'exception sur
     * 4xx/5xx (RestClient.exchange n'applique pas les status handlers) — pour
     * les flux où l'appelant branche sur le code HTTP (404/503/202…).
     */
    private ApiResult exchangeForResult(Supplier<RestClient.RequestBodySpec> request) {
        try {
            return request.get().exchange((req, res) -> {
                Map<String, Object> parsed;
                try {
                    parsed = res.bodyTo(MAP_TYPE);
                } catch (Exception ignored) {
                    parsed = Map.of();
                }
                return new ApiResult(res.getStatusCode().value(), parsed == null ? Map.of() : parsed);
            });
        } catch (Exception e) {
            log.warn("Appel Minecraft en échec: {}", e.getMessage());
            return new ApiResult(500, Map.of());
        }
    }

    private static String currentJwt() {
        // WS dispatch threads have no HTTP RequestContext; the WS layer stashes
        // the session JWT in WsAuthContext before invoking handlers/dispatcher.
        String wsJwt = WsAuthContext.get();
        if (wsJwt != null) return wsJwt;
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
