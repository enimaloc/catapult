package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.web.PrivacyController;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PrivacyController.class)
@TestPropertySource(properties = {
    "spring.messages.basename=lang/messages",
    "spring.messages.use-code-as-default-message=false"
})
class PrivacyTemplateTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ExperimentService experimentService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        BuildProperties buildProperties() {
            Properties props = new Properties();
            props.setProperty("privacy.fr.last-update", "2024-01-01T00:00:00Z");
            props.setProperty("privacy.en.last-update", "2024-01-01T00:00:00Z");
            return new BuildProperties(props);
        }
    }

    @Test
    void privacy_titleIsPrivacyPolicy() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("h1").text()).contains("confidentialité");
    }

    @Test
    void privacy_containsTwitchAndSteamData() throws Exception {
        Document doc = renderPage();
        String body = doc.body().text();
        assertThat(body).contains("Twitch");
        assertThat(body).contains("Steam");
    }

    @Test
    void privacy_containsRightsSection() throws Exception {
        Document doc = renderPage();
        assertThat(doc.body().text()).containsAnyOf("Paramètres", "supprimer");
    }

    @Test
    void privacy_footerIsPresent() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("footer.footer")).isNotEmpty();
    }

    @Test
    void privacy_noUnresolvedI18nKeys() throws Exception {
        Document doc = renderPage();
        assertThat(doc.body().text()).doesNotContain("??");
    }

    private Document renderPage() throws Exception {
        String html = mockMvc.perform(get("/privacy").locale(Locale.FRENCH))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }
}
