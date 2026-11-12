package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitCorrectionDto;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

@WebMvcTest(controllers = AdminRateLimitsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminRateLimitsControllerTest {
    static final String BASE = "/admin/rate-limits";
    static final String HOST = "HOST store.steampowered.com";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt");
    }

    @Test
    void aCorrection_isForwardedAsSent_andItsStatusRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCorrectRateLimit(HOST, new LearnedRateLimitCorrectionDto(20, 60, null, "ROUTE")))
                .thenReturn(200);
        when(apiService.adminCorrectRateLimit(HOST, new LearnedRateLimitCorrectionDto(5, null, null, null)))
                .thenReturn(400);

        mvc.perform(admin(post(BASE + "/correct").param("id", HOST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limitCalls\":20,\"limitWindowSeconds\":60,\"resumeSeconds\":null,\"scope\":\"ROUTE\"}")))
                .andExpect(status().isOk());
        mvc.perform(admin(post(BASE + "/correct").param("id", HOST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limitCalls\":5}"))).andExpect(status().isBadRequest());
    }

    @Test
    void aReset_relaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminResetRateLimit(HOST)).thenReturn(404);

        mvc.perform(admin(post(BASE + "/reset").param("id", HOST))).andExpect(status().isNotFound());
    }

    @Test
    void aMember_isRefused() throws Exception {
        when(apiService.isAdmin()).thenReturn(false);

        mvc.perform(admin(post(BASE + "/reset").param("id", HOST))).andExpect(status().isForbidden());
        verify(apiService, never()).adminResetRateLimit(any());
    }
}
