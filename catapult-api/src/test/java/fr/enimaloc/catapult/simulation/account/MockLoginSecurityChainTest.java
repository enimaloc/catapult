package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.config.OAuth2ClientConfig;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.security.AuthCodeStore;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.security.JwtService;
import fr.enimaloc.catapult.security.Oauth2LoginFailureHandler;
import fr.enimaloc.catapult.security.SecurityConfig;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real OAuth2 login chain ({@link SecurityConfig}, with Twitch's client registration) under {@code dev,mock} and
 * the simulation: the choice page stands in front of Spring Security's redirect to Twitch, and {@code ?real=1} still
 * gets that redirect.
 */
@WebMvcTest(
        controllers = MockLoginController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({SecurityConfig.class, OAuth2ClientConfig.class, MockLoginNonces.class, MockLoginSecurityChainTest.Guard.class})
@ActiveProfiles({"dev", "mock"})
@TestPropertySource(properties = {
        "catapult.simulation.enabled=true",
        "sentry.environment=development",
        "twitch.client-id=test-client",
        "twitch.client-secret=test-secret",
        "app.base-url=http://localhost:8080",
        "app.web-url=http://web.test",
})
class MockLoginSecurityChainTest {

    private static final String AUTHORIZE = "/oauth2/authorization/twitch";

    /** The guard under its scanned name, which {@code @DependsOn("mockGuard")} refers to. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Guard {
        @Bean
        MockGuard mockGuard(Environment environment, @Value("${sentry.environment:}") String sentryEnvironment) {
            return new MockGuard(environment, sentryEnvironment);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean FictitiousAccountService accounts;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean TwitchLoginSuccessHandler loginSuccessHandler;
    @MockitoBean Oauth2LoginFailureHandler loginFailureHandler;
    @MockitoBean JwtService jwtService;
    @MockitoBean AuthCodeStore codeStore;

    @Test
    void loopback_authorization_shows_the_choice_page() throws Exception {
        UserAccount alice = new UserAccount();
        alice.setId(UUID.randomUUID());
        alice.setTwitchId("mock-a1b2c3d4");
        alice.setTwitchUsername("alice");
        when(accounts.fictitiousAccounts()).thenReturn(List.of(alice));

        String page = mvc.perform(get(AUTHORIZE).queryParam("web_state", "s-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Continue with Twitch").contains("alice")
                .contains(MockLoginController.LOGIN_PATH + alice.getId());
    }

    @Test
    void real_login_redirects_to_twitch() throws Exception {
        String location = mvc.perform(get(AUTHORIZE).queryParam("real", "1"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("https://id.twitch.tv/oauth2/authorize");
    }
}
