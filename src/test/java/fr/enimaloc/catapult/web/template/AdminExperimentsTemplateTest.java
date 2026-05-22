package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.*;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.StatisticsService;
import fr.enimaloc.catapult.web.AdminExperimentsController;
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

@WebMvcTest(AdminExperimentsController.class)
@TestPropertySource(properties = "spring.messages.basename=lang/messages")
class AdminExperimentsTemplateTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ExperimentRepository experimentRepository;
    @MockitoBean ExperimentEventRepository eventRepository;
    @MockitoBean ExperimentFeedbackRepository feedbackRepository;
    @MockitoBean ExperimentAssignmentRepository assignmentRepository;
    @MockitoBean ExperimentOverrideRepository overrideRepository;
    @MockitoBean ExperimentAssignmentRuleRepository ruleRepository;
    @MockitoBean ExperimentVariantRepository variantRepository;
    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean StatisticsService statisticsService;
    @MockitoBean ExperimentService experimentService;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;

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
        when(experimentRepository.findAll()).thenReturn(List.of());

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
    void adminExperiments_htmlHasLangAttribute() throws Exception {
        Document doc = renderPage("/admin/experiments");
        assertThat(doc.select("html[lang]")).isNotEmpty();
    }

    @Test
    void adminExperiments_titleIsNotBlank() throws Exception {
        Document doc = renderPage("/admin/experiments");
        assertThat(doc.select("title").text()).isNotBlank();
    }

    @Test
    void adminExperiments_headingIsPresent() throws Exception {
        Document doc = renderPage("/admin/experiments");
        assertThat(doc.select("h2")).isNotEmpty();
    }

    @Test
    void adminExperiments_noUnresolvedI18nKeys() throws Exception {
        Document doc = renderPage("/admin/experiments");
        assertThat(doc.body().text()).doesNotContain("??");
    }

    private Document renderPage(String url) throws Exception {
        String html = mockMvc.perform(get(url).locale(Locale.FRENCH).with(authentication(adminAuth)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }
}
