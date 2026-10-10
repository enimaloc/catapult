package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ObsSessionController.class)
@Import(WebSecurityConfig.class)
class ObsSessionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void enabled_returnsTheConnectionUncached() throws Exception {
        when(apiService.obsConnection()).thenReturn(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw"));

        mvc.perform(get("/me/obs"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"obsHost\":\"10.0.0.2\",\"obsPort\":4456,\"obsPassword\":\"pw\"}"));
    }

    @Test
    void loggedOutOrDisabled_noContent() throws Exception {
        when(apiService.obsConnection()).thenReturn(null);

        mvc.perform(get("/me/obs")).andExpect(status().isNoContent());
    }
}
