package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.web.MockLoginController;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MockLoginController.class)
@ActiveProfiles("mock-web")
@TestPropertySource(properties = "spring.messages.basename=lang/messages")
class MockLoginTemplateTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ExperimentService experimentService;

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
            return http.build();
        }
    }

    @BeforeEach
    void setUp() {
        when(userAccountRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void mockLogin_htmlHasLangAttribute() throws Exception {
        Document doc = renderPage("/mock-login");
        assertThat(doc.select("html[lang]")).isNotEmpty();
    }

    @Test
    void mockLogin_titleIsNotBlank() throws Exception {
        Document doc = renderPage("/mock-login");
        assertThat(doc.select("title").text()).isNotBlank();
    }

    @Test
    void mockLogin_formAndSelectArePresent() throws Exception {
        Document doc = renderPage("/mock-login");
        assertThat(doc.select("form")).isNotEmpty();
        assertThat(doc.select("select[name=userId]")).isNotEmpty();
    }

    @Test
    void mockLogin_selectHasAriaLabel() throws Exception {
        Document doc = renderPage("/mock-login");
        assertThat(doc.select("select[aria-label]")).isNotEmpty();
    }

    @Test
    void mockLogin_noUnresolvedI18nKeys() throws Exception {
        Document doc = renderPage("/mock-login");
        assertThat(doc.body().text()).doesNotContain("??");
    }

    private Document renderPage(String url) throws Exception {
        String html = mockMvc.perform(get(url).locale(Locale.FRENCH))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }
}
