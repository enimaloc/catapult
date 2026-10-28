package fr.enimaloc.catapult.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** catapult-api's public paths, relayed by catapult-web; a local HTTP server stands in for catapult-api. */
@WebMvcTest(ApiRelayController.class)
@Import({WebSecurityConfig.class, CustomBindingsCorsConfig.class})
class ApiRelayControllerTest {

    /** What the stand-in catapult-api received last. */
    record Received(String method, String uri, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> String.join(",", e.getValue())).findFirst().orElse(null);
        }
    }

    private static final HttpServer API = start();
    private static final AtomicReference<Received> RECEIVED = new AtomicReference<>();
    private static volatile Consumer<HttpExchange> answer;

    @Autowired MockMvc mvc;

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.set(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        Map.copyOf(exchange.getRequestHeaders()),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                answer.accept(exchange);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void api(DynamicPropertyRegistry registry) {
        registry.add("catapult.backend-url", () -> "http://127.0.0.1:" + API.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stop() {
        API.stop(0);
    }

    @BeforeEach
    void reset() {
        RECEIVED.set(null);
        answer = exchange -> respond(exchange, 200, "ok", Map.of());
    }

    private static void respond(HttpExchange exchange, int status, String body, Map<String, List<String>> headers) {
        try {
            headers.forEach((name, values) -> exchange.getResponseHeaders().put(name, values));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void relaysTheRequest_asTheVisitorSentIt_withTheForwardingCatapultWebVouchesFor() throws Exception {
        mvc.perform(post("/api/user/abc/items").queryParam("q", "a b")
                        .with(request -> {
                            request.setScheme("https");
                            request.setServerName("catapult.example");
                            request.setServerPort(443);
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .header("Authorization", "Bearer t")
                        .header("Cookie", "CATAPULT_API_SESSION=s1")
                        .header("X-Forwarded-For", "6.6.6.6")
                        .header("Connection", "keep-alive, X-Custom")
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isOk()).andExpect(content().string("ok"));

        Received received = RECEIVED.get();
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.uri()).isEqualTo("/api/user/abc/items?q=a%20b");
        assertThat(received.body()).isEqualTo("{\"a\":1}");
        assertThat(received.header("Authorization")).isEqualTo("Bearer t");
        assertThat(received.header("Cookie")).isEqualTo("CATAPULT_API_SESSION=s1");
        assertThat(received.header("Content-Type")).startsWith("application/json");
        assertThat(received.header("X-Forwarded-For")).isEqualTo("203.0.113.7");
        assertThat(received.header("X-Real-IP")).isEqualTo("203.0.113.7");
        assertThat(received.header("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(received.header("X-Forwarded-Host")).isEqualTo("catapult.example");
        assertThat(received.header("X-Forwarded-Port")).isEqualTo("443");
        assertThat(received.header("Connection")).isNotEqualTo("keep-alive, X-Custom");
    }

    /** The login's redirect to Twitch, and the cookies it sets, must reach the browser untouched. */
    @Test
    void relaysTheAnswer_redirectsAndCookiesIncluded_neverFollowingIt() throws Exception {
        answer = exchange -> respond(exchange, 302, "", Map.of(
                "Location", List.of("https://id.twitch.tv/oauth2/authorize?x=1"),
                "Set-Cookie", List.of("CATAPULT_API_SESSION=s2; Path=/; HttpOnly", "other=1; Path=/")));

        MockHttpServletResponse response = mvc.perform(get("/oauth2/authorization/twitch").queryParam("web_state", "st"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://id.twitch.tv/oauth2/authorize?x=1"))
                .andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie")).contains("CATAPULT_API_SESSION=s2; Path=/; HttpOnly", "other=1; Path=/");
        assertThat(RECEIVED.get().uri()).isEqualTo("/oauth2/authorization/twitch?web_state=st");
    }

    @Test
    void relaysEveryPathCatapultApiServesTheBrowser() throws Exception {
        for (String path : List.of("/login/oauth2/code/twitch", "/api/connect/steam/callback", "/api/game/1",
                "/api/v3/api-docs", "/api/v3/api-docs/swagger-config")) {
            mvc.perform(get(path)).andExpect(status().isOk());
            assertThat(RECEIVED.get().uri()).isEqualTo(path);
        }
    }

    /**
     * A Stream Deck or a script reaches the custom bindings API through the site: its token-only PUT/DELETE needs no
     * CSRF token or session here (catapult-api's own chain authenticates the Bearer), and catapult-api's answer,
     * a refusal included, comes back as is.
     */
    @Test
    void relaysTheCustomBindingsApi_aTokenOnlyPutWithoutCsrf_andItsStatus() throws Exception {
        answer = exchange -> respond(exchange, 204, "", Map.of());

        mvc.perform(put("/api/me/custom-bindings/active").header("Authorization", "Bearer cat_TOKEN")
                        .contentType("application/json").content("{\"idOrName\":\"Art\"}"))
                .andExpect(status().isNoContent());

        Received received = RECEIVED.get();
        assertThat(received.method()).isEqualTo("PUT");
        assertThat(received.uri()).isEqualTo("/api/me/custom-bindings/active");
        assertThat(received.header("Authorization")).isEqualTo("Bearer cat_TOKEN");

        answer = exchange -> respond(exchange, 401, "", Map.of());
        mvc.perform(delete("/api/me/custom-bindings/active")).andExpect(status().isUnauthorized());
        assertThat(RECEIVED.get().method()).isEqualTo("DELETE");
        assertThat(RECEIVED.get().uri()).isEqualTo("/api/me/custom-bindings/active");

        answer = exchange -> respond(exchange, 200, "{\"bindings\":[]}", Map.of("Content-Type", List.of("application/json")));
        mvc.perform(get("/api/me/custom-bindings").header("Authorization", "Bearer cat_TOKEN"))
                .andExpect(status().isOk()).andExpect(content().string("{\"bindings\":[]}"));
        assertThat(RECEIVED.get().uri()).isEqualTo("/api/me/custom-bindings");
    }

    /** The public API by resource, the read-only token in the query included: relayed as sent. */
    @Test
    void relaysThePublicApiByResource() throws Exception {
        for (String path : List.of("/api/me/game", "/api/me/game/summary", "/api/me/bindings/x1",
                "/api/games/igdb/1", "/api/games/steam/2", "/api/games/xbox/9N", "/api/games/dtdd/3")) {
            mvc.perform(get(path).queryParam("token", "catr_READ")).andExpect(status().isOk());
            assertThat(RECEIVED.get().uri()).isEqualTo(path + "?token=catr_READ");
        }
    }

    @Test
    void theRetiredCustomBindingsApi_isNoLongerRelayed() throws Exception {
        mvc.perform(get("/api/v2/custom-bindings").header("Authorization", "Bearer cat_TOKEN"))
                .andExpect(status().is4xxClientError());
        assertThat(RECEIVED.get()).isNull();
    }

    // --- CORS on the custom bindings API: a page of any origin (Twitchat) calls it with its token alone ---

    private static final String ORIGIN = "https://example.org";

    @Test
    void customBindingsPreflight_isAnsweredHere_withoutAuthentication() throws Exception {
        for (String path : List.of("/api/me/custom-bindings", "/api/me/custom-bindings/active")) {
            mvc.perform(options(path)
                            .header("Origin", ORIGIN)
                            .header("Access-Control-Request-Method", "PUT")
                            .header("Access-Control-Request-Headers", "authorization, content-type"))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                    .andExpect(header().string("Access-Control-Allow-Methods", "GET,PUT,DELETE,OPTIONS"))
                    .andExpect(header().string("Access-Control-Allow-Headers",
                            org.hamcrest.Matchers.containsStringIgnoringCase("authorization")))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
        assertThat(RECEIVED.get()).isNull();
    }

    /** catapult-api answers with its own CORS headers too: the browser must see Allow-Origin once only. */
    @Test
    void customBindingsAnswers_carryTheCorsHeadersOnce_refusalsIncluded() throws Exception {
        answer = exchange -> respond(exchange, 401, "", Map.of(
                "Access-Control-Allow-Origin", List.of("*"), "Vary", List.of("Origin")));

        MockHttpServletResponse response = mvc.perform(put("/api/me/custom-bindings/active")
                        .header("Origin", ORIGIN).header("Authorization", "Bearer cat_BAD"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        assertThat(response.getHeaders("Access-Control-Allow-Origin")).containsExactly("*");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(RECEIVED.get().header("Authorization")).isEqualTo("Bearer cat_BAD");
    }

    @Test
    void customBindingsWithASessionCookieOnly_getCatapultApis401() throws Exception {
        answer = exchange -> respond(exchange, 401, "", Map.of());

        mvc.perform(delete("/api/me/custom-bindings/active").header("Origin", ORIGIN)
                        .cookie(new Cookie("SESSION", "web-session")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        assertThat(RECEIVED.get().header("Authorization")).isNull();
    }

    @Test
    void otherRelayedPaths_getNoCorsFromCatapultWeb() throws Exception {
        mvc.perform(get("/api/game/1").header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/user/abc")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /** A relayed path whose CORS is catapult-api's own (its @CrossOrigin): its headers still come through. */
    @Test
    void otherRelayedPaths_keepCatapultApisOwnCorsHeaders() throws Exception {
        answer = exchange -> respond(exchange, 200, "ok", Map.of("Access-Control-Allow-Origin", List.of("*")));

        MockHttpServletResponse response = mvc.perform(get("/api/user/abc").header("Origin", ORIGIN))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getHeaders("Access-Control-Allow-Origin")).containsExactly("*");
    }

    @Test
    void passesCatapultApisErrorsThrough_asItsOwn() throws Exception {
        answer = exchange -> respond(exchange, 503, "{\"busy\":true}", Map.of("Content-Type", List.of("application/json")));

        mvc.perform(get("/api/game/1")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string("{\"busy\":true}"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")));
    }

    @Test
    void anUnreachableCatapultApi_answersTheMaintenancePage() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ApiRelayController relay = new ApiRelayController("http://127.0.0.1:" + closedPort);
        MockHttpServletResponse response = new MockHttpServletResponse();

        relay.relay(new MockHttpServletRequest("GET", "/oauth2/authorization/twitch"), response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("Maintenance");
    }

    @Test
    void refusesABodyOverItsLimit() throws Exception {
        mvc.perform(post("/api/user/abc").content(new byte[ApiRelayController.MAX_BODY_BYTES + 1]))
                .andExpect(status().isContentTooLarge());
        assertThat(RECEIVED.get()).isNull();
    }

    @Test
    void catapultWebsOwnPathsAreNotRelayed() throws Exception {
        mvc.perform(get("/api/channels/x")).andExpect(status().isNotFound());
        assertThat(RECEIVED.get()).isNull();
    }

    @Test
    void relaysExactlyThePathsTheSecurityLeavesToCatapultApi() throws Exception {
        var mapping = ApiRelayController.class.getMethod("relay", jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class).getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(mapping.value()).containsExactly(ApiRelayController.PATHS);
    }
}
