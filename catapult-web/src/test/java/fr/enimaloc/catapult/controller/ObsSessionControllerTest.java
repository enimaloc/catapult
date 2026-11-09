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
import org.springframework.mock.web.MockHttpSession;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(apiService.obsConnection()).thenReturn(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "auto"));

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

    /** An admin acting as a member: their browser never connects to the member's OBS. */
    @Test
    void impersonating_noContent_withoutAskingCatapultApi() throws Exception {
        when(apiService.obsConnection()).thenReturn(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "auto"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin-jwt");

        mvc.perform(get("/me/obs").session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(apiService, never()).obsConnection();
    }
}
