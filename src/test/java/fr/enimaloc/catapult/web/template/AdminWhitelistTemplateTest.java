package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.WhitelistService;
import fr.enimaloc.catapult.web.AdminWhitelistController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminWhitelistController.class)
@TestPropertySource(properties = {
    "spring.messages.basename=lang/messages",
    "spring.messages.use-code-as-default-message=false",
    "spring.messages.fallback-to-system-locale=false"
})
class AdminWhitelistTemplateTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean WhitelistService whitelistService;
    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean ExperimentService experimentService;

    private UsernamePasswordAuthenticationToken adminAuth;

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }

    @BeforeEach
    void setUp() {
        when(whitelistService.findAll()).thenReturn(List.of());
        when(whitelistService.isEnabled()).thenReturn(false);

        UserAccount account = new UserAccount();
        account.setTwitchId("admin-id");
        account.setStatus(UserAccount.Status.ACTIVE);

        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        CatapultOAuth2User principal = new CatapultOAuth2User(oAuth2User, account, true);

        adminAuth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    @Test
    void page_rendersWithEmptyList() throws Exception {
        String html = mockMvc.perform(get("/admin/whitelist").locale(Locale.ENGLISH).with(authentication(adminAuth)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(html).contains("Administration");
        assertThat(html).contains("whitelist");
    }

    @Test
    void page_showsEnabledStatus_whenWhitelistActive() throws Exception {
        when(whitelistService.isEnabled()).thenReturn(true);

        String html = mockMvc.perform(get("/admin/whitelist").locale(Locale.ENGLISH).with(authentication(adminAuth)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(html).contains("Whitelist active");
    }

    @Test
    void page_showsDisabledStatus_whenWhitelistInactive() throws Exception {
        when(whitelistService.isEnabled()).thenReturn(false);

        String html = mockMvc.perform(get("/admin/whitelist").locale(Locale.ENGLISH).with(authentication(adminAuth)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(html).contains("Whitelist disabled");
    }
}
