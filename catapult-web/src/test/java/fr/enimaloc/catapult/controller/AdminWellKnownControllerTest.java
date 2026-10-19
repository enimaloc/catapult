package fr.enimaloc.catapult.controller;

import java.util.List;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminWellKnownController.class)
@Import({WebSecurityConfig.class, ModelFiller.class, WellKnownCache.class})
class AdminWellKnownControllerTest {
    static final String BODY = "{\"location\":\"ROOT\",\"path\":\"riot.txt\",\"status\":200,\"headers\":[],\"contentType\":\"text/plain\",\"content\":\"x\"}";
    static final WellKnownFileRequest REQUEST = new WellKnownFileRequest(ROOT, "riot.txt", 200, List.of(), "text/plain", "x");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;
    @MockitoSpyBean WellKnownCache cache;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    @Test
    void createUpdateDelete_areForwardedForAnAdmin_andEmptyTheCache() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateWellKnownFile(any())).thenReturn(201);
        when(apiService.adminUpdateWellKnownFile(anyLong(), any())).thenReturn(200);
        when(apiService.adminDeleteWellKnownFile(anyLong())).thenReturn(204);

        mvc.perform(admin(post("/admin/meta/files"))).andExpect(status().isCreated());
        mvc.perform(admin(post("/admin/meta/files/4"))).andExpect(status().isOk());
        mvc.perform(admin(post("/admin/meta/files/4/delete"))).andExpect(status().isNoContent());

        verify(apiService).adminCreateWellKnownFile(REQUEST);
        verify(apiService).adminUpdateWellKnownFile(4, REQUEST);
        verify(apiService).adminDeleteWellKnownFile(4);
        verify(cache, org.mockito.Mockito.times(3)).clear();
    }

    @Test
    void failures_areRelayed_withoutEmptyingTheCache() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateWellKnownFile(any())).thenReturn(409);
        when(apiService.adminUpdateWellKnownFile(anyLong(), any())).thenReturn(400);
        when(apiService.adminDeleteWellKnownFile(anyLong())).thenReturn(502);

        mvc.perform(admin(post("/admin/meta/files"))).andExpect(status().isConflict());
        mvc.perform(admin(post("/admin/meta/files/4"))).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/meta/files/4/delete"))).andExpect(status().isBadGateway());

        verify(cache, never()).clear();
    }

    @Test
    void aVisitorAndAMember_areRefused_andTheApiIsNeverCalled() throws Exception {
        for (String url : new String[]{"/admin/meta/files", "/admin/meta/files/4", "/admin/meta/files/4/delete"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url))).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminCreateWellKnownFile(any());
        verify(apiService, never()).adminUpdateWellKnownFile(anyLong(), any());
        verify(apiService, never()).adminDeleteWellKnownFile(anyLong());
        verify(cache, never()).clear();
    }

    @Test
    void aBodyWithoutStatusOrHeaders_isForwardedAsIs() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateWellKnownFile(any())).thenReturn(201);
        mvc.perform(post("/admin/meta/files").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"ROOT\",\"path\":\"riot.txt\",\"contentType\":\"text/plain\",\"content\":\"x\"}"))
                .andExpect(status().isCreated());
        verify(apiService).adminCreateWellKnownFile(new WellKnownFileRequest(ROOT, "riot.txt", null, null, "text/plain", "x"));
    }
}
