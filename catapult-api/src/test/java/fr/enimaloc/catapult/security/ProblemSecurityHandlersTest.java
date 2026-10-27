package fr.enimaloc.catapult.security;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import java.util.List;
import java.util.UUID;
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

/**
 * The security chains' refusals on the whole application: an internal route gets a problem with a top-level
 * {@code code}, and keeps the status and {@code WWW-Authenticate} header of the bearer handlers; a frozen route keeps
 * its empty answer.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.config.additional-location=file:./src/main/resources/")
@TestPropertySource(locations = "classpath:full-context.properties")
@Import(FullContextContainers.class)
@AutoConfigureMockMvc
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProblemSecurityHandlersTest {

    private static final String REMOTE = "203.0.113.7";

    @MockitoBean
    TwitchService twitchService;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    JwtService jwtService;

    UserAccount owner;
    String memberJwt;

    @BeforeAll
    void fixtures() {
        owner = account("problem-security-owner", "problem-security-owner-twitch");
        UserAccount member = account("problem-security-member", "problem-security-member-twitch");
        memberJwt = "Bearer " + jwtService.generateForUser(member, List.of("ROLE_USER"));
    }

    /** Nobody moderates anything, so the member is neither owner nor moderator of the channel. */
    @BeforeEach
    void noModerator() {
        when(twitchService.getModeratedChannelIds(any())).thenReturn(List.of());
    }

    @Test
    void bodyIsAProblemWithATopLevelCode() throws Exception {
        mvc.perform(remote(get("/api/internal/admin/flags")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.instance").value("/api/internal/admin/flags"))
                .andExpect(jsonPath("$.properties").doesNotExist());
    }

    @Test
    void badTokenKeepsTheBearerHeader() throws Exception {
        mvc.perform(remote(get("/api/internal/admin/flags")).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\"")))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void memberOnAdminIs403Problem() throws Exception {
        mvc.perform(remote(get("/api/internal/admin/flags")).header("Authorization", memberJwt))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer error=\"insufficient_scope\"")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @Test
    void memberOnInternalAdminIs403Problem() throws Exception {
        mvc.perform(remote(get("/api/internal/admin/flags")).header("Authorization", memberJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void channelDenialIs403Problem() throws Exception {
        mvc.perform(remote(get("/api/internal/users/{userId}/settings", owner.getId()))
                        .header("Authorization", memberJwt))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer error=\"insufficient_scope\"")))
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void frozenBadTokenStaysEmpty() throws Exception {
        mvc.perform(remote(get("/api/user/game/{u}", UUID.randomUUID().toString()))
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\"")))
                .andExpect(header().doesNotExist("Content-Type"))
                .andExpect(content().string(""));
    }

    @Test
    void customBindingsWithoutTokenIs401Problem() throws Exception {
        mvc.perform(remote(get("/api/v2/custom-bindings")).header("Origin", "https://example.org"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));
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
