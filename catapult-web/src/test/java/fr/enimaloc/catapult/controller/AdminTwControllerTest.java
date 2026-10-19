package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.TwRebuild;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminTwController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminTwControllerTest {
    static final String BASE = "/admin/tw";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void create_relaysTheDefinition_andTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateTw("gore", "Gore", 3)).thenReturn(201);
        when(apiService.adminCreateTw("dup", "Dup", 0)).thenReturn(409);

        mvc.perform(admin(post(BASE), "{\"id\":\"gore\",\"label\":\" Gore \",\"sortOrder\":3}")).andExpect(status().isCreated());
        mvc.perform(admin(post(BASE), "{\"id\":\"dup\",\"label\":\"Dup\"}")).andExpect(status().isConflict());
    }

    @Test
    void create_checksIdAndLabel() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        for (String body : new String[]{
                "{\"id\":\"Bad Id\",\"label\":\"L\"}", "{\"id\":\"\",\"label\":\"L\"}", "{\"label\":\"L\"}",
                "{\"id\":\"" + "a".repeat(41) + "\",\"label\":\"L\"}",
                "{\"id\":\"ok\",\"label\":\"" + "x".repeat(81) + "\"}", "{\"id\":\"ok\",\"label\":\"  \"}", "{\"id\":\"ok\"}",
                "{\"id\":\"ok\",\"label\":\"L\",\"sortOrder\":-1}"}) {
            mvc.perform(admin(post(BASE), body)).andExpect(status().isBadRequest());
        }
        verify(apiService, never()).adminCreateTw(any(), any(), anyInt());
    }

    @Test
    void rebuild_relaysEachMode_andItsStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRebuildTws(TwRebuild.NON_OVERRIDDEN)).thenReturn(202);
        when(apiService.adminRebuildTws(TwRebuild.UNTOUCHED)).thenReturn(503);
        when(apiService.adminRebuildTws(TwRebuild.FORCE_PINNED)).thenReturn(502);

        mvc.perform(admin(post(BASE + "/rebuild/non-overridden"), "{}")).andExpect(status().isAccepted());
        mvc.perform(admin(post(BASE + "/rebuild/untouched"), "{}")).andExpect(status().isServiceUnavailable());
        mvc.perform(admin(post(BASE + "/rebuild/force-pinned"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void rebuild_unknownMode_is404() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/rebuild/everything"), "{}")).andExpect(status().isNotFound());
        verify(apiService, never()).adminRebuildTws(any());
    }

    @Test
    void actions_refuseVisitorsAndMembers_andNeedCsrf() throws Exception {
        for (String url : new String[]{BASE, BASE + "/rebuild/untouched", BASE + "/rebuild/force-pinned"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), "{}")).andExpect(status().isForbidden());
        }
        // an admin without a CSRF token is refused too
        when(apiService.isAdmin()).thenReturn(true);
        for (String url : new String[]{BASE, BASE + "/rebuild/untouched"}) {
            mvc.perform(post(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"ok\",\"label\":\"L\"}")).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminCreateTw(any(), any(), anyInt());
        verify(apiService, never()).adminRebuildTws(any());
    }
}
