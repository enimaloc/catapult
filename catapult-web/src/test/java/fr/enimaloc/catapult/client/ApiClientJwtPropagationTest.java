package fr.enimaloc.catapult.client;

import com.sun.net.httpserver.HttpServer;
import fr.enimaloc.catapult.web.ws.auth.WsAuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link ApiClient#currentJwt()} fallback chain:
 * <ol>
 *   <li>{@link WsAuthContext} (for WS dispatch threads) takes precedence</li>
 *   <li>fallback to the {@code RequestContextHolder}'s HttpSession when no WS JWT is set</li>
 *   <li>no JWT bound → no Authorization header sent</li>
 * </ol>
 *
 * <p>We don't bother spinning up Spring; we point {@link ApiClient} at a tiny
 * in-process {@link HttpServer} and inspect the request headers it observes.</p>
 */
class ApiClientJwtPropagationTest {

    private HttpServer server;
    private AtomicReference<String> lastAuth;
    private ApiClient apiClient;

    @BeforeEach
    void setUp() throws Exception {
        lastAuth = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/echo", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        apiClient = new ApiClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        WsAuthContext.clear();
        if (server != null) server.stop(0);
    }

    @Test
    void uses_ws_auth_context_jwt_when_set() {
        WsAuthContext.set("ws-jwt-token");

        apiClient.get("/api/echo", Map.class);

        assertThat(lastAuth.get()).isEqualTo("Bearer ws-jwt-token");
    }

    @Test
    void omits_authorization_header_when_no_jwt_anywhere() {
        // no WsAuthContext, no RequestContextHolder
        apiClient.get("/api/echo", Map.class);

        assertThat(lastAuth.get()).isNull();
    }

    @Test
    void ws_auth_context_takes_precedence_when_both_paths_have_jwt() {
        // Set up a fake HTTP request context with a different JWT.
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "http-jwt");
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setSession(session);
        var attrs = new org.springframework.web.context.request.ServletRequestAttributes(req);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);
        try {
            WsAuthContext.set("ws-jwt-wins");
            apiClient.get("/api/echo", Map.class);
            assertThat(lastAuth.get()).isEqualTo("Bearer ws-jwt-wins");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void falls_back_to_request_context_jwt_when_ws_context_empty() {
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "http-fallback-jwt");
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setSession(session);
        var attrs = new org.springframework.web.context.request.ServletRequestAttributes(req);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);
        try {
            apiClient.get("/api/echo", Map.class);
            assertThat(lastAuth.get()).isEqualTo("Bearer http-fallback-jwt");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }
}
