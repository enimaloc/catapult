package fr.enimaloc.catapult.client;

import com.sun.net.httpserver.HttpServer;
import fr.enimaloc.catapult.web.ws.auth.WsAuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiClientMinecraftTest {

    private HttpServer server;
    private ApiClient apiClient;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        apiClient = new ApiClient("http://localhost:" + server.getAddress().getPort());
        WsAuthContext.set("test-token");
    }

    @AfterEach
    void tearDown() {
        WsAuthContext.clear();
        server.stop(0);
    }

    private void respond(String path, int status, String json) {
        server.createContext(path, exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
            exchange.close();
        });
    }

    @Test
    void minecraftEnroll_mapsErrorStatusInsteadOfSwallowing() {
        respond("/api/connect/minecraft", 404, "{\"message\":\"Joueur introuvable\"}");

        ApiClient.ApiResult result = apiClient.minecraftEnroll("nexistepas");

        assertThat(result.status()).isEqualTo(404);
        assertThat(lastMethod.get()).isEqualTo("POST");
    }

    @Test
    void minecraftEnroll_success_returnsBody() {
        respond("/api/connect/minecraft", 200,
                "{\"status\":\"PENDING\",\"minecraftName\":\"jeb_\",\"serviceAccountUsername\":\"CatapultBot1\"}");

        ApiClient.ApiResult result = apiClient.minecraftEnroll("jeb_");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).containsEntry("status", "PENDING");
    }

    @Test
    void adminMinecraftCreate_pending202_isExposed() {
        respond("/api/admin/minecraft-accounts", 202, "{\"status\":\"PENDING\"}");

        ApiClient.ApiResult result = apiClient.adminMinecraftCreate("device-1", "Bot1");

        assertThat(result.status()).isEqualTo(202);
    }

    @Test
    void adminMinecraftPatch_usesPatchVerb() {
        UUID id = UUID.randomUUID();
        respond("/api/admin/minecraft-accounts/" + id, 200, "{}");

        boolean ok = apiClient.adminMinecraftPatch(id, Map.of("enabled", false));

        assertThat(ok).isTrue();
        assertThat(lastMethod.get()).isEqualTo("PATCH");
    }

    @Test
    void adminMinecraftDelete_conflict409_isExposed() {
        UUID id = UUID.randomUUID();
        respond("/api/admin/minecraft-accounts/" + id, 409, "{\"error\":\"liens\"}");

        assertThat(apiClient.adminMinecraftDelete(id)).isEqualTo(409);
    }

    @Test
    void minecraftEnroll_propagatesBearerOnExchangePath() {
        respond("/api/connect/minecraft", 200, "{\"status\":\"PENDING\"}");

        apiClient.minecraftEnroll("jeb_");

        assertThat(lastAuth.get()).isEqualTo("Bearer test-token");
    }
}
