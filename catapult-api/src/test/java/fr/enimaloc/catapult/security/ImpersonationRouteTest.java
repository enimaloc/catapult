package fr.enimaloc.catapult.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin impersonation still reaches the target's channel now that a channel is addressed by its account id: the
 * token the admin route mints is the target's, so the channel routes see the target as their owner. The admin's own
 * token, admin or not, is no owner of someone else's channel.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.config.additional-location=file:./src/main/resources/")
@TestPropertySource(locations = "classpath:full-context.properties")
@Import(FullContextContainers.class)
@AutoConfigureMockMvc
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImpersonationRouteTest {

    /** Not loopback: only the admin's role lets it through the admin routes. */
    private static final String REMOTE = "203.0.113.7";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @MockitoBean
    TwitchService twitchService;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    JwtService jwtService;

    UserAccount target;
    String adminJwt;

    @BeforeAll
    void fixtures() {
        UserAccount admin = account("impersonation-admin", "impersonation-admin-twitch");
        target = account("impersonation-target", "impersonation-target-twitch");
        adminJwt = jwtService.generateForUser(admin, List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    /** Nobody moderates anything: the admin reaches the target's channel only as the target. */
    @BeforeEach
    void noModerator() {
        when(twitchService.getModeratedChannelIds(any())).thenReturn(List.of());
    }

    @Test
    void impersonatedTokenReachesTheTargetsChannelById() throws Exception {
        String body = mvc.perform(remote(post("/api/admin/impersonate"))
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + target.getTwitchUsername() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String impersonated = JSON.readTree(body).path("token").asString();

        mvc.perform(remote(get("/api/internal/users/{userId}/settings", target.getId()))
                        .header("Authorization", "Bearer " + impersonated))
                .andExpect(status().isOk());
    }

    @Test
    void adminTokenAloneIsRefusedOnTheChannel() throws Exception {
        mvc.perform(remote(get("/api/internal/users/{userId}/settings", target.getId()))
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder remote(MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(REMOTE);
            return r;
        });
    }

    private UserAccount account(String username, String twitchId) {
        UserAccount account = new UserAccount();
        account.setTwitchUsername(username);
        account.setTwitchId(twitchId);
        account.setStatus(UserAccount.Status.ACTIVE);
        return accounts.save(account);
    }
}
