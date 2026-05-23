package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.web.HomeController;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(HomeController.class)
@TestPropertySource(properties = {
    "spring.messages.basename=lang/messages",
    "spring.messages.use-code-as-default-message=false",
    "steam.enabled=true",
    "steam.api-key=testkey",
    "xbox.enabled=true",
    "battlenet.enabled=true"
})
class HomeTemplateTest {

    @Autowired MockMvc mockMvc;
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
    void home_returns200AndHomeView() throws Exception {
        mockMvc.perform(get("/").locale(Locale.FRENCH))
            .andExpect(status().isOk())
            .andExpect(view().name("home"));
    }

    @Test
    void home_noUnresolvedI18nKeys() throws Exception {
        Document doc = renderPage();
        assertThat(doc.body().text()).doesNotContain("??");
    }

    @Test
    void home_twitchCTAButtonPresent() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select(".btn-twitch")).isNotEmpty();
    }

    @Test
    void home_heroSectionPresent() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select(".home-hero")).isNotEmpty();
    }

    @Test
    void home_allSourceBadgesVisibleWhenAllEnabled() throws Exception {
        Document doc = renderPage();
        String body = doc.body().text();
        assertThat(body).contains("Steam");
        assertThat(body).contains("Xbox");
        assertThat(body).contains("Battle.net");
    }

    @Test
    void home_footerIsPresent() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("footer.footer")).isNotEmpty();
    }

    private Document renderPage() throws Exception {
        String html = mockMvc.perform(get("/").locale(Locale.FRENCH))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }
}
