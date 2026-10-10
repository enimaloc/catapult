package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiMeObsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiMeObsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TwitchatWidgetSettingsService twitchatWidgetSettingsService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void enabled_returnsTheConnectionWithItsPassword() throws Exception {
        when(twitchatWidgetSettingsService.enabledConfig(userId))
                .thenReturn(Optional.of(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw")));

        mvc.perform(get("/api/me/obs").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"obsHost\":\"10.0.0.2\",\"obsPort\":4456,\"obsPassword\":\"pw\"}"));
    }

    @Test
    void disabledOrUnset_noContent() throws Exception {
        when(twitchatWidgetSettingsService.enabledConfig(userId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/me/obs").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isNoContent());
    }
}
