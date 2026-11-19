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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChannelEmergencyController.class)
@Import(WebSecurityConfig.class)
class ChannelEmergencyControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void resume_forwardsToTheApiAndAnswersItsStatus() throws Exception {
        when(apiService.resumeChannelEmergency("enimaloc")).thenReturn(200);

        mvc.perform(post("/channel/enimaloc/emergency/resume").with(csrf())).andExpect(status().isNoContent());

        verify(apiService).resumeChannelEmergency("enimaloc");
    }

    @Test
    void resume_answersCatapultApisRefusal() throws Exception {
        when(apiService.resumeChannelEmergency("enimaloc")).thenReturn(403);

        mvc.perform(post("/channel/enimaloc/emergency/resume").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void resume_is502WhenTheApiIsUnreachable() throws Exception {
        when(apiService.resumeChannelEmergency("enimaloc")).thenReturn(502);

        mvc.perform(post("/channel/enimaloc/emergency/resume").with(csrf())).andExpect(status().isBadGateway());
    }
}
