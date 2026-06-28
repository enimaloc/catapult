package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.codec.msg.ResponseMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for {@link HtmxWsDispatcher}: spins up a real
 * Spring context, fabricates a {@link WsSession}, and asserts the
 * {@link ResponseMessage} the dispatcher produces matches HTMX expectations.
 *
 * <p>A nested {@link TestConfiguration} adds throwaway controllers exercising
 * the GET/POST/DELETE/preauth/oob/large-body paths.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(HtmxWsDispatcherTest.HtmxTestController.class)
@Testcontainers
class HtmxWsDispatcherTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Autowired
    HtmxWsDispatcher dispatcher;

    @MockitoBean
    ApiClient apiClient;

    @Test
    void get_existing_endpoint_returns_html_with_200() {
        ResponseMessage resp = dispatcher.dispatch("h-1", anonymousSession(),
                Map.of("method", "GET", "path", "/__htmx-test/hello"));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.html()).contains("Hello htmx");
    }

    @Test
    void post_with_form_params_reaches_controller() {
        ResponseMessage resp = dispatcher.dispatch("h-2", anonymousSession(),
                Map.of("method", "POST", "path", "/__htmx-test/echo",
                        "params", Map.of("name", "Skyrim", "qty", 7)));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.html()).contains("name=Skyrim").contains("qty=7");
    }

    @Test
    void delete_method_is_routed_correctly() {
        ResponseMessage resp = dispatcher.dispatch("h-3", anonymousSession(),
                Map.of("method", "DELETE", "path", "/__htmx-test/item/42"));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.status()).isEqualTo(200);
        // Thymeleaf wraps "42" in a <span> per the template.
        assertThat(resp.html()).contains("deleted").contains("42");
    }

    @Test
    void preauthorize_admin_route_as_anonymous_returns_403() {
        ResponseMessage resp = dispatcher.dispatch("h-4", anonymousSession(),
                Map.of("method", "GET", "path", "/__htmx-test/admin-only"));

        assertThat(resp.ok()).isTrue(); // dispatch itself succeeded
        assertThat(resp.status()).isEqualTo(403);
    }

    @Test
    void hx_trigger_response_header_propagated_into_triggers() {
        ResponseMessage resp = dispatcher.dispatch("h-5", anonymousSession(),
                Map.of("method", "GET", "path", "/__htmx-test/with-trigger"));

        assertThat(resp.triggers()).isNotNull().containsKey("saved");
    }

    @Test
    void hx_swap_oob_fragments_extracted_from_response_html() {
        ResponseMessage resp = dispatcher.dispatch("h-6", anonymousSession(),
                Map.of("method", "GET", "path", "/__htmx-test/with-oob"));

        assertThat(resp.oob()).isNotNull().hasSize(1);
        ResponseMessage.OobSwap oob = resp.oob().get(0);
        assertThat(oob.target()).isEqualTo("#count");
        assertThat(oob.html()).contains("<span hx-swap-oob=\"innerHTML\" id=\"count\">5</span>");
    }

    @Test
    void response_larger_than_256kb_returns_too_large_error() {
        ResponseMessage resp = dispatcher.dispatch("h-7", anonymousSession(),
                Map.of("method", "GET", "path", "/__htmx-test/big"));

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error()).isNotNull();
        assertThat(resp.error().code()).isEqualTo("HTMX_RESPONSE_TOO_LARGE");
    }

    @Test
    void multipart_content_type_is_refused_as_multipart_fallback() {
        ResponseMessage resp = dispatcher.dispatch("h-8", anonymousSession(),
                Map.of("method", "POST", "path", "/__htmx-test/echo",
                        "headers", Map.of("Content-Type", "multipart/form-data; boundary=xxx")));

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error()).isNotNull();
        assertThat(resp.error().code()).isEqualTo("MULTIPART_FALLBACK");
    }

    @Test
    void missing_path_yields_invalid_params_error() {
        ResponseMessage resp = dispatcher.dispatch("h-9", anonymousSession(),
                Map.of("method", "GET"));

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error().code()).isEqualTo("INVALID_PARAMS");
    }

    @Test
    void authenticated_session_propagates_security_context_to_controller() {
        WsSession admin = adminSession();
        ResponseMessage resp = dispatcher.dispatch("h-10", admin,
                Map.of("method", "GET", "path", "/__htmx-test/admin-only",
                        "csrfToken", admin.csrfToken()));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.html()).contains("admin ok");
    }

    @Test
    void authenticated_session_missing_csrf_token_is_rejected() {
        ResponseMessage resp = dispatcher.dispatch("h-csrf-1", adminSession(),
                Map.of("method", "POST", "path", "/__htmx-test/echo",
                        "params", Map.of("k", "v")));

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error().code()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void authenticated_session_wrong_csrf_token_is_rejected() {
        ResponseMessage resp = dispatcher.dispatch("h-csrf-2", adminSession(),
                Map.of("method", "POST", "path", "/__htmx-test/echo",
                        "csrfToken", "definitely-not-the-server-token"));

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error().code()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void anonymous_session_skips_csrf_check() {
        ResponseMessage resp = dispatcher.dispatch("h-csrf-3", anonymousSession(),
                Map.of("method", "GET", "path", "/__htmx-test/hello"));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.status()).isEqualTo(200);
    }

    private static WsSession anonymousSession() {
        WebSocketSession spring = mock(WebSocketSession.class);
        when(spring.getId()).thenReturn("test-anon-" + UUID.randomUUID());
        return new WsSession(spring);
    }

    private static WsSession adminSession() {
        WebSocketSession spring = mock(WebSocketSession.class);
        when(spring.getId()).thenReturn("test-admin-" + UUID.randomUUID());
        WsSession ws = new WsSession(spring);
        ws.authenticate(UUID.randomUUID(), Set.of("ROLE_USER", "ROLE_ADMIN"));
        ws.setCsrfToken("server-issued-test-csrf-" + UUID.randomUUID());
        return ws;
    }

    @Controller
    @EnableMethodSecurity
    static class HtmxTestController {

        @GetMapping("/__htmx-test/hello")
        @ResponseBody
        String hello() {
            return "<p id=\"hello\">Hello htmx</p>";
        }

        @PostMapping("/__htmx-test/echo")
        String echo(@RequestParam Map<String, String> all, Model model) {
            model.addAttribute("entries", all);
            return "test/echo-fragment";
        }

        @DeleteMapping("/__htmx-test/item/{id}")
        String delete(@PathVariable String id, Model model) {
            model.addAttribute("id", id);
            return "test/delete-fragment";
        }

        @GetMapping("/__htmx-test/admin-only")
        @ResponseBody
        @PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
        String adminOnly() {
            return "<p>admin ok</p>";
        }

        @GetMapping("/__htmx-test/with-trigger")
        String withTrigger(jakarta.servlet.http.HttpServletResponse resp, Model model) {
            resp.setHeader("HX-Trigger", "{\"saved\":{\"name\":\"x\"}}");
            return "test/empty-fragment";
        }

        @GetMapping("/__htmx-test/with-oob")
        @ResponseBody
        String withOob() {
            return "<ul id=\"main\"><li>a</li></ul>"
                    + "<span hx-swap-oob=\"innerHTML\" id=\"count\">5</span>";
        }

        @GetMapping("/__htmx-test/big")
        @ResponseBody
        String big() {
            // 300 KB > MAX_RESPONSE_BYTES (256 KB)
            return "x".repeat(300 * 1024);
        }
    }
}
