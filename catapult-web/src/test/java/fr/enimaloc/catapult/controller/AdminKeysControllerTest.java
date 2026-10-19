package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminKeysController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminKeysControllerTest {
    static final String STEAM = "/admin/keys/steam";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void actions_relayTheirStatus_forEachProvider() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddApiKey(ApiKeyProvider.STEAM, "0123456789ABCDEF0123456789ABCDEF")).thenReturn(204);
        when(apiService.adminDeleteApiKey(ApiKeyProvider.DTDD, "id/1 é")).thenReturn(404);
        when(apiService.adminRefreshApiKeys(ApiKeyProvider.DTDD)).thenReturn(502);

        mvc.perform(admin(post(STEAM + "/add"), "{\"apiKey\":\" 0123456789ABCDEF0123456789ABCDEF \"}")).andExpect(status().isNoContent());
        mvc.perform(admin(post("/admin/keys/dtdd/delete"), "{\"keyId\":\"id/1 é\"}")).andExpect(status().isNotFound());
        mvc.perform(admin(post("/admin/keys/dtdd/refresh"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void add_400_isRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddApiKey(ApiKeyProvider.STEAM, "nope")).thenReturn(400);

        mvc.perform(admin(post(STEAM + "/add"), "{\"apiKey\":\"nope\"}")).andExpect(status().isBadRequest());
        verify(apiService).adminAddApiKey(ApiKeyProvider.STEAM, "nope");
    }

    @Test
    void blankInputs_are400_withoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(STEAM + "/add"), "{\"apiKey\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(STEAM + "/add"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(STEAM + "/delete"), "{}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminAddApiKey(any(), any());
        verify(apiService, never()).adminDeleteApiKey(any(), any());
    }

    @Test
    void unknownProvider_is404() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post("/admin/keys/epic/add"), "{\"apiKey\":\"k\"}")).andExpect(status().isNotFound());
        mvc.perform(admin(post("/admin/keys/epic/delete"), "{\"keyId\":\"k\"}")).andExpect(status().isNotFound());
        mvc.perform(admin(post("/admin/keys/epic/refresh"), "{}")).andExpect(status().isNotFound());
        verify(apiService, never()).adminAddApiKey(any(), any());
        verify(apiService, never()).adminDeleteApiKey(any(), any());
        verify(apiService, never()).adminRefreshApiKeys(any());
    }

    @Test
    void visitorIs401_memberIs403_andTheApiIsNeverCalled() throws Exception {
        mvc.perform(post(STEAM + "/add").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"k\"}"))
                .andExpect(status().isUnauthorized());
        when(apiService.isAdmin()).thenReturn(false);
        mvc.perform(admin(post(STEAM + "/add"), "{\"apiKey\":\"k\"}")).andExpect(status().isForbidden());
        mvc.perform(admin(post(STEAM + "/refresh"), "{}")).andExpect(status().isForbidden());
        verify(apiService, never()).adminAddApiKey(any(), any());
        verify(apiService, never()).adminRefreshApiKeys(any());
    }

    @Test
    void withoutCsrf_isRefused() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(post(STEAM + "/add").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"k\"}")).andExpect(status().isForbidden());
        verify(apiService, never()).adminAddApiKey(any(), any());
    }
}
