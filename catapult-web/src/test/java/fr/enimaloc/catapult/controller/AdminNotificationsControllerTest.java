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
        when(apiService.adminSendNotification(any())).thenReturn(new ApiClient.Result<>(201, null));

        mvc.perform(admin(post("/admin/notifications"), BODY)).andExpect(status().isCreated())
                .andExpect(content().string(""));

        verify(apiService).adminSendNotification(
                new AdminNotificationCreateBody("Hi", "**x**", Severity.WARNING, null, null, null, null));
    }

    @Test
    void create_forAnExperiment_relaysTheIds_andHowManyWereTold() throws Exception {
        UUID experiment = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminSendNotification(any())).thenReturn(new ApiClient.Result<>(201, 12));

        mvc.perform(admin(post("/admin/notifications"), "{\"title\":\"Hi\",\"body\":\"x\",\"severity\":\"INFO\","
                        + "\"audience\":\"EXPERIMENT\",\"experimentId\":\"" + experiment + "\",\"variantId\":\"" + variant + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"sent\":12}"));

        verify(apiService).adminSendNotification(new AdminNotificationCreateBody("Hi", "x", Severity.INFO, null, null, null,
                null, "EXPERIMENT", experiment, variant));
    }

    @Test
    void audienceCount_isRelayedForAnAdmin_and503WhenTheApiFails() throws Exception {
        UUID experiment = UUID.randomUUID();
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminNotificationAudienceCount(experiment, null)).thenReturn(12L);

        mvc.perform(admin(post("/admin/notifications/audience-count"),
                        "{\"audience\":\"EXPERIMENT\",\"experimentId\":\"" + experiment + "\"}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"count\":12}"));
        UUID failing = UUID.randomUUID();
        when(apiService.adminNotificationAudienceCount(failing, null)).thenReturn(null);
        mvc.perform(admin(post("/admin/notifications/audience-count"),
                        "{\"audience\":\"EXPERIMENT\",\"experimentId\":\"" + failing + "\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void audienceCount_isRefusedToAVisitorAndAMember() throws Exception {
        String body = "{\"audience\":\"EXPERIMENT\",\"experimentId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/admin/notifications/audience-count").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(admin(post("/admin/notifications/audience-count"), body)).andExpect(status().isForbidden());
        verify(apiService, never()).adminNotificationAudienceCount(any(), any());
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
        when(apiService.adminSendNotification(any())).thenReturn(new ApiClient.Result<>(400, null));
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
        verify(apiService, never()).adminSendNotification(any());
        verify(apiService, never()).adminDeleteNotification(any());
        verify(apiService, never()).adminPreviewNotification(any());
    }
}
