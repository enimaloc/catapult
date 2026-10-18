package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationSnapshotDto;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = NotificationsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class NotificationsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    @Test
    void menu_rendersTheSnapshot() throws Exception {
        NotificationSnapshotDto snapshot = new NotificationSnapshotDto(List.of(), 0);
        when(apiService.notificationSnapshot()).thenReturn(snapshot);

        mvc.perform(get("/spa/notifications/menu").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/notifications :: menu"))
                .andExpect(model().attribute("snapshot", snapshot));
    }

    @Test
    void menu_rendersItemsCappedAtTen_withTheBodyUnescaped() throws Exception {
        List<NotificationDto> items = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add(new NotificationDto(UUID.randomUUID(), "T<" + i, "<b>body</b>", Severity.WARNING,
                    "https://example.com", "Go", null, java.time.Instant.parse("2026-10-18T10:00:00Z"), i == 0));
        }
        when(apiService.notificationSnapshot()).thenReturn(new NotificationSnapshotDto(items, 11));

        String html = mvc.perform(get("/spa/notifications/menu").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("data-unread=\"11\"", "<b>body</b>", "T&lt;0", "data-severity=\"WARNING\"")
                .contains("id=\"notification-item-template\"");
        org.assertj.core.api.Assertions.assertThat(html.split("data-severity=\"WARNING\"", -1).length - 1).isEqualTo(10);
    }

    @Test
    void menu_withTheApiDown_rendersWithoutSnapshot() throws Exception {
        when(apiService.notificationSnapshot()).thenReturn(null);

        String html = mvc.perform(get("/spa/notifications/menu").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/notifications :: menu"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("data-unread=\"-1\"");
    }

    @Test
    void menu_isRefusedToAVisitor() throws Exception {
        mvc.perform(get("/spa/notifications/menu")).andExpect(status().isUnauthorized());
        verify(apiService, never()).notificationSnapshot();
    }

    @Test
    void read_relaysCatapultApisStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(apiService.markNotificationRead(id)).thenReturn(204);

        mvc.perform(post("/spa/notifications/{id}/read", id).with(csrf())
                .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isNoContent());
        verify(apiService).markNotificationRead(id);
    }

    @Test
    void readAll_relaysCatapultApisStatus() throws Exception {
        when(apiService.markAllNotificationsRead()).thenReturn(502);

        mvc.perform(post("/spa/notifications/read-all").with(csrf())
                .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isBadGateway());
    }

    @Test
    void writes_areRefusedToAVisitor() throws Exception {
        mvc.perform(post("/spa/notifications/{id}/read", UUID.randomUUID()).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/spa/notifications/read-all").with(csrf())).andExpect(status().isUnauthorized());

        verify(apiService, never()).markAllNotificationsRead();
    }
}
