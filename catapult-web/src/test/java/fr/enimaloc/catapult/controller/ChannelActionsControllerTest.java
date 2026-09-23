package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChannelActionsController.class)
@Import(WebSecurityConfig.class)
class ChannelActionsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void toggleBot_callsApiServiceAndReturns204() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/bot").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).toggleBot("enimaloc");
    }

    @Test
    void recheckGame_callsApiServiceAndReturns204() throws Exception {
        mvc.perform(post("/channel/enimaloc/game/recheck").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).recheckGame("enimaloc");
    }

    @Test
    void toggleBot_withoutCsrf_isRejected() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/bot"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cclToggle_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/ccl-toggle").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());

        verify(apiService).cclToggle("enimaloc", "abc-123", true);
    }

    @Test
    void ignoredToggle_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/ignored-toggle").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ignored\":true}"))
                .andExpect(status().isNoContent());

        verify(apiService).ignoredToggle("enimaloc", "abc-123", true);
    }

    @Test
    void deleteBinding_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/delete").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).deleteBinding("enimaloc", "abc-123");
    }

    @Test
    void updateBinding_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"twitchGameId\":\"509658\",\"twitchGameName\":\"Celeste\",\"ccls\":[\"violent-graphic\"]}"))
                .andExpect(status().isNoContent());

        verify(apiService).updateBinding("enimaloc", "abc-123", "509658", "Celeste",
                java.util.Set.of("violent-graphic"));
    }
}
