package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.service.ExperimentService;
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

import java.util.Properties;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(PrivacyController.class)
@TestPropertySource(properties = {
    "spring.messages.basename=lang/messages",
    "spring.messages.use-code-as-default-message=false"
})
class PrivacyControllerTest {

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
    void getPrivacy_withoutAuthentication_returns200() throws Exception {
        mockMvc.perform(get("/privacy"))
            .andExpect(status().isOk())
            .andExpect(view().name("privacy"));
    }
}
