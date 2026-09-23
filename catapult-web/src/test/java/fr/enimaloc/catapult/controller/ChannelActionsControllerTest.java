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
}
