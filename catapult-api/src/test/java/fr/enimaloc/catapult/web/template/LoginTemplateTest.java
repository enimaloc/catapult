package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.web.LoginController;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LoginController.class)
@TestPropertySource(properties = "spring.messages.basename=lang/messages")
class LoginTemplateTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
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

    @Test
    void login_htmlHasLangAttribute() throws Exception {
        Document doc = renderPage("/login");
        assertThat(doc.select("html[lang]")).isNotEmpty();
    }

    @Test
    void login_titleIsNotBlank() throws Exception {
        Document doc = renderPage("/login");
        assertThat(doc.select("title").text()).isNotBlank();
    }

    @Test
    void login_twitchButtonIsPresent() throws Exception {
        Document doc = renderPage("/login");
        assertThat(doc.select(".btn-twitch")).isNotEmpty();
    }

    @Test
    void login_noUnresolvedI18nKeys() throws Exception {
        Document doc = renderPage("/login");
        assertThat(doc.body().text()).doesNotContain("??");
    }

    private Document renderPage(String url) throws Exception {
        String html = mockMvc.perform(get(url).locale(Locale.FRENCH))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }
}
