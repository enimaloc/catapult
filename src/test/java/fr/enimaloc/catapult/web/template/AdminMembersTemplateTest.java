package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.web.AdminMembersController;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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

@WebMvcTest(AdminMembersController.class)
@TestPropertySource(properties = {
    "spring.messages.basename=lang/messages",
    "spring.messages.use-code-as-default-message=false"
})
class AdminMembersTemplateTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean StreamStateService streamStateService;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean ExperimentService experimentService;
    @MockitoBean AccountService accountService;
    @MockitoBean AdminMigrationService adminMigrationService;

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
        when(userAccountRepository.findAll()).thenReturn(List.of());

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
    void adminMembers_htmlHasLangAttribute() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("html[lang]")).isNotEmpty();
    }

    @Test
    void adminMembers_titleIsNotBlank() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("title").text()).isNotBlank();
    }

    @Test
    void adminMembers_headingIsPresent() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("h2")).isNotEmpty();
    }

    @Test
    void adminMembers_noUnresolvedI18nKeys() throws Exception {
        Document doc = renderPage();
        assertThat(doc.body().text()).doesNotContain("??");
    }

    @Test
    void adminMembers_inactiveMember_rendersWithoutErrors() throws Exception {
        UserAccount inactive = new UserAccount();
        inactive.setId(java.util.UUID.randomUUID());
        inactive.setTwitchId(null);
        inactive.setTwitchUsername(null);
        inactive.setStatus(UserAccount.Status.INACTIVE);

        when(userAccountRepository.findAll()).thenReturn(List.of(inactive));
        when(streamStateService.isLive(inactive)).thenReturn(false);

        Document doc = renderPage();
        assertThat(doc.select("html")).isNotEmpty();
        assertThat(doc.body().text()).doesNotContain("??");
        // INACTIVE badge should appear
        assertThat(doc.body().text()).containsIgnoringCase("inactif");
        // Unlink Twitch form should NOT appear for INACTIVE accounts
        assertThat(doc.select("form[action*='twitch/unlink']")).isEmpty();
    }

    private Document renderPage() throws Exception {
        String html = mockMvc.perform(get("/admin/members").locale(Locale.FRENCH).with(authentication(adminAuth)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }
}
