package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
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

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminBroadcastController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminBroadcastControllerTest {
    static final String SCHEDULED = "{\"name\":\"maintenance.scheduled\",\"startsAt\":\"2026-10-20T20:00:00Z\","
            + "\"durationMinutes\":30,\"message\":\"m\"}";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(String body) {
        return post("/admin/broadcast").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aBody_reachesTheApiAsItsSubtype_andItsStatusIsRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminBroadcast(any())).thenReturn(202);

        mvc.perform(admin(SCHEDULED)).andExpect(status().isAccepted());

        verify(apiService).adminBroadcast(
                new BroadcastRequestDto.MaintenanceScheduled(Instant.parse("2026-10-20T20:00:00Z"), 30, "m"));
    }

    @Test
    void failures_areRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminBroadcast(any())).thenReturn(400, 429, 502);

        mvc.perform(admin(SCHEDULED)).andExpect(status().isBadRequest());
        mvc.perform(admin(SCHEDULED)).andExpect(status().isTooManyRequests());
        mvc.perform(admin(SCHEDULED)).andExpect(status().isBadGateway());
    }

    @Test
    void anUnknownName_isRefusedWithoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin("{\"name\":\"maintenance.imminent\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin("{\"name\":\"nope\"}")).andExpect(status().isBadRequest());

        verify(apiService, never()).adminBroadcast(any());
    }

    @Test
    void aVisitorAndAMember_areRefused_andTheApiIsNeverCalled() throws Exception {
        mvc.perform(post("/admin/broadcast").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(SCHEDULED))
                .andExpect(status().isUnauthorized());
        mvc.perform(admin(SCHEDULED)).andExpect(status().isForbidden());
        verify(apiService, never()).adminBroadcast(any());
    }
}
