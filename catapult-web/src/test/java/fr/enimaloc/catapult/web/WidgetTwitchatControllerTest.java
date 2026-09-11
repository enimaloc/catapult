package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.client.ApiHealthService;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = WidgetTwitchatController.class)
@Import(WebSecurityConfig.class)
class WidgetTwitchatControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiClient apiClient;
    // GlobalModelAdvice (a global @ControllerAdvice picked up by @WebMvcTest regardless of the
    // controllers filter) depends on ApiHealthService for its apiAvailable model attribute.
    @MockitoBean ApiHealthService apiHealthService;

    @Test
    void widgetPage_rendersConfigFromApi() throws Exception {
        UUID token = UUID.randomUUID();
        when(apiClient.get("/api/twitchat/widget/{uuid}", Map.class, token.toString()))
                .thenReturn(Map.of("obsHost", "127.0.0.1", "obsPort", 4455, "obsPassword", "pw"));

        mvc.perform(get("/widget/twitchat/{uuid}", token))
                .andExpect(status().isOk())
                .andExpect(view().name("widget/twitchat"))
                .andExpect(model().attribute("obsHost", "127.0.0.1"))
                .andExpect(model().attribute("obsPort", 4455))
                .andExpect(model().attribute("widgetToken", token.toString()));
    }

    @Test
    void actionPage_executed_setsExecutedTrue() throws Exception {
        UUID token = UUID.randomUUID();
        when(apiClient.post("/api/twitchat/actions/{token}", null, Map.class, token.toString()))
                .thenReturn(Map.of("result", "EXECUTED"));

        mvc.perform(get("/widget/twitchat/action/{token}", token))
                .andExpect(status().isOk())
                .andExpect(view().name("widget/twitchat-action"))
                .andExpect(model().attribute("executed", true));
    }
}
