package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminEmergencyController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminEmergencyControllerTest {
    static final String BASE = "/admin/emergency";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt");
    }

    @Test
    void aStop_isForwarded_andItsStatusRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminEmergencyStop("XBOX")).thenReturn(200);
        when(apiService.adminEmergencyStop("NOPE")).thenReturn(400);

        mvc.perform(admin(post(BASE + "/stop").param("provider", "XBOX"))).andExpect(status().isOk());
        mvc.perform(admin(post(BASE + "/stop").param("provider", "NOPE"))).andExpect(status().isBadRequest());

        verify(apiService).adminEmergencyStop("XBOX");
    }

    @Test
    void aResume_isForwarded_andItsStatusRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminEmergencyResume("ALL")).thenReturn(200);

        mvc.perform(admin(post(BASE + "/resume").param("provider", "ALL"))).andExpect(status().isOk());
        verify(apiService).adminEmergencyResume("ALL");
    }

    @Test
    void anUnreachableApi_is502() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminEmergencyResume("XBOX")).thenReturn(502);

        mvc.perform(admin(post(BASE + "/resume").param("provider", "XBOX"))).andExpect(status().isBadGateway());
    }

    @Test
    void aMember_isRefused() throws Exception {
        when(apiService.isAdmin()).thenReturn(false);

        mvc.perform(admin(post(BASE + "/stop").param("provider", "XBOX"))).andExpect(status().isForbidden());
        mvc.perform(admin(post(BASE + "/resume").param("provider", "XBOX"))).andExpect(status().isForbidden());
        verify(apiService, never()).adminEmergencyStop(any());
        verify(apiService, never()).adminEmergencyResume(any());
    }
}
