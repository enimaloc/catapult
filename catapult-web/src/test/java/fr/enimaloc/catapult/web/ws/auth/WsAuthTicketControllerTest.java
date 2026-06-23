package fr.enimaloc.catapult.web.ws.auth;

import fr.enimaloc.catapult.security.CatapultWebUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Testcontainers
class WsAuthTicketControllerTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    WsTicketStore ticketStore;

    // JwtSessionAuthFilter calls catapult-api at startup-fetched URL; mock so it doesn't blow up.
    @MockitoBean
    fr.enimaloc.catapult.client.ApiClient apiClient;

    @MockitoBean
    fr.enimaloc.catapult.client.ApiHealthService apiHealthService;

    @Test
    @WithCatapultUser
    void authenticated_get_returns_ticket() throws Exception {
        mvc.perform(get("/ws/auth-ticket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value(matchesPattern("^[A-Za-z0-9_-]+$")))
                .andExpect(jsonPath("$.expiresInSeconds").value(10));
    }

    @Test
    @WithAnonymousUser
    void anonymous_get_returns_401() throws Exception {
        mvc.perform(get("/ws/auth-ticket"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithCatapultUser
    void issued_ticket_is_consumable_via_store() throws Exception {
        var result = mvc.perform(get("/ws/auth-ticket"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String ticket = body.replaceFirst(".*\"ticket\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        var snap = ticketStore.consume(ticket);
        assertThat(snap).isPresent();
        assertThat(snap.get().userId()).isEqualTo(WithCatapultUserFactory.FIXED_ID);
    }

    // ── test-only annotation & factory to install a CatapultWebUser principal ──

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = WithCatapultUserFactory.class)
    public @interface WithCatapultUser {
    }

    public static class WithCatapultUserFactory implements WithSecurityContextFactory<WithCatapultUser> {
        public static final UUID FIXED_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

        @Override
        public SecurityContext createSecurityContext(WithCatapultUser ann) {
            CatapultWebUser user = new CatapultWebUser(
                    FIXED_ID, "twitch-1", "tester",
                    null, "ACTIVE", List.of("ROLE_USER"), null);
            var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            SecurityContext ctx = new SecurityContextImpl();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }
}
