package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiTwitchatWidgetController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class ApiTwitchatWidgetControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean TwitchatWidgetSettingsRepository widgetSettingsRepository;
    @MockitoBean TokenEncryptionService tokenEncryptionService;
    @MockitoBean TwitchatActionExecutor actionExecutor;

    @Test
    void defaults_returnsAllFiveEventTypes() throws Exception {
        mvc.perform(get("/api/twitchat/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.STREAM_STARTED.message").value("Le bot Catapult est actif."))
                .andExpect(jsonPath("$.STREAM_STARTED.icon").value("live"))
                .andExpect(jsonPath("$.CATEGORY_CHANGED_BY_CATAPULT.actions.DISABLE_BOT.theme").value("alert"));
    }
}
