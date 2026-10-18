package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.notification.Severity;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminNotificationsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminNotificationsControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static final String BODY = "{\"title\":\"Hi\",\"body\":\"**x**\",\"severity\":\"WARNING\",\"ctaUrl\":null,"
            + "\"ctaLabel\":null,\"expiresAt\":null,\"targetUserId\":null}";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void create_isForwardedForAnAdmin_andItsStatusRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateNotification(any())).thenReturn(201);

        mvc.perform(admin(post("/admin/notifications"), BODY)).andExpect(status().isCreated());

        verify(apiService).adminCreateNotification(
                new AdminNotificationCreateBody("Hi", "**x**", Severity.WARNING, null, null, null, null));
    }

    @Test
    void delete_isForwardedForAnAdmin_andItsStatusRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteNotification(ID)).thenReturn(204);

        mvc.perform(admin(post("/admin/notifications/" + ID + "/delete"), "{}")).andExpect(status().isNoContent());
    }

    @Test
    void failures_areRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateNotification(any())).thenReturn(400);
        when(apiService.adminDeleteNotification(ID)).thenReturn(502);
        when(apiService.adminPreviewNotification("x")).thenReturn(new ApiClient.Result<>(502, null));
        // an older catapult-api without the route: its 404, not a 502 reported as a server error
        when(apiService.adminPreviewNotification("y")).thenReturn(new ApiClient.Result<>(404, null));

        mvc.perform(admin(post("/admin/notifications"), BODY)).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/notifications/" + ID + "/delete"), "{}")).andExpect(status().isBadGateway());
        mvc.perform(admin(post("/admin/notifications/preview"), "{\"body\":\"x\"}")).andExpect(status().isBadGateway());
        mvc.perform(admin(post("/admin/notifications/preview"), "{\"body\":\"y\"}")).andExpect(status().isNotFound());
    }

    @Test
    void preview_answersTheRenderedHtmlAsText() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminPreviewNotification("**x**")).thenReturn(new ApiClient.Result<>(200, "<p><strong>x</strong></p>"));

        mvc.perform(admin(post("/admin/notifications/preview"), "{\"body\":\"**x**\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<p><strong>x</strong></p>"));
    }

    @Test
    void aVisitorAndAMember_areRefused_andTheApiIsNeverCalled() throws Exception {
        for (String url : new String[]{"/admin/notifications", "/admin/notifications/" + ID + "/delete",
                "/admin/notifications/preview"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), BODY)).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminCreateNotification(any());
        verify(apiService, never()).adminDeleteNotification(any());
        verify(apiService, never()).adminPreviewNotification(any());
    }
}
