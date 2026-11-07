package fr.enimaloc.catapult.simulation.panel;

import fr.enimaloc.catapult.config.OAuth2ClientConfig;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.security.Oauth2LoginFailureHandler;
import fr.enimaloc.catapult.security.SecurityConfig;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.GameSourceResolver;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationActions;
import fr.enimaloc.catapult.simulation.SimulationJournal;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import fr.enimaloc.catapult.simulation.account.FictitiousAccountService;
import fr.enimaloc.catapult.simulation.account.MockGuard;
import fr.enimaloc.catapult.simulation.account.MockLoginNonces;
import fr.enimaloc.catapult.simulation.account.TwitchUserLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The panel behind the real chains under {@code dev,mock} and the simulation: {@code /mock} falls in
 * {@link SecurityConfig}'s open chain, {@code /api/internal/mock/**} in {@link ApiSecurityConfig}'s, which lets a
 * loopback caller in without a token; neither chain's CSRF stops the panel's JSON writes. A remote caller gets the
 * chain's 401 on the API, and the controller's 404 on the page.
 */
@WebMvcTest(
        controllers = {MockPanelController.class, MockPanelApiController.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, SecurityConfig.class, OAuth2ClientConfig.class, MockLoginNonces.class,
        MockPanelSecurityChainTest.Beans.class})
@ActiveProfiles({"dev", "mock"})
@TestPropertySource(properties = {
        "catapult.simulation.enabled=true",
        "sentry.environment=development",
        "twitch.client-id=test-client",
        "twitch.client-secret=test-secret",
        "app.base-url=http://localhost:8080",
        "app.web-url=http://web.test",
})
class MockPanelSecurityChainTest {

    private static final String REMOTE = "203.0.113.7";

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        /** The guard under its scanned name, which {@code @DependsOn("mockGuard")} refers to. */
        @Bean
        MockGuard mockGuard(Environment environment, @Value("${sentry.environment:}") String sentryEnvironment) {
            return new MockGuard(environment, sentryEnvironment);
        }

        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean FictitiousAccountService accounts;
    @MockitoBean UserAccountRepository userAccounts;
    @MockitoBean SimulationRegistry registry;
    @MockitoBean SimulationJournal journal;
    @MockitoBean SimulationActions actions;
    @MockitoBean GameStateService gameState;
    @MockitoBean StreamStateService streamState;
    @MockitoBean TwitchEventSubService eventSub;
    @MockitoBean GameSourceResolver games;
    @MockitoBean TwitchUserLookup twitchUsers;
    @MockitoBean UserApiTokenService userApiTokenService;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean TwitchLoginSuccessHandler loginSuccessHandler;
    @MockitoBean Oauth2LoginFailureHandler loginFailureHandler;

    @Test
    void loopback_gets_the_page_without_a_session() throws Exception {
        mvc.perform(get("/mock"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/mock/panel.js")).andExpect(status().isOk());
    }

    @Test
    void remote_gets_404_on_the_page() throws Exception {
        mvc.perform(get("/mock").with(r -> {
                    r.setRemoteAddr(REMOTE);
                    return r;
                }))
                .andExpect(status().isNotFound());
    }

    /** No token and no CSRF token: the loopback write goes through both chains to the registry. */
    @Test
    void loopback_json_write_is_not_blocked_by_csrf() throws Exception {
        UserAccount alice = new UserAccount();
        alice.setId(UUID.randomUUID());
        alice.setTwitchId("mock-a1b2c3d4");
        when(userAccounts.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(accounts.account(alice.getId())).thenReturn(Optional.of(alice));
        when(registry.setting(any(), any())).thenReturn(CallSetting.ok());

        mvc.perform(put("/api/internal/mock/accounts/{id}/settings/{key}", alice.getId(),
                        "twitch.helix.update-channel")
                        .header(MockPanelApiController.PANEL_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TIMEOUT\"}"))
                .andExpect(status().isOk());

        verify(registry).set(eq(alice.getId()), eq(SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL),
                eq(CallSetting.timeout()));
    }

    @Test
    void remote_gets_the_chains_401_on_the_api() throws Exception {
        mvc.perform(get("/api/internal/mock/accounts").with(r -> {
                    r.setRemoteAddr(REMOTE);
                    return r;
                }))
                .andExpect(status().isUnauthorized());
    }
}
