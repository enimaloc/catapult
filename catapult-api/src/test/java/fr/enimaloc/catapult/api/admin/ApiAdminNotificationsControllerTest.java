package fr.enimaloc.catapult.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewDto;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.notification.NotificationRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminNotificationsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import(ApiUserResolver.class)
class ApiAdminNotificationsControllerTest {
    @MockitoBean ChannelAccessService channelAccessService;

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean NotificationService service;
    @MockitoBean NotificationRepository repo;
    @MockitoBean UserAccountRepository userRepo;

    @Test
    void create_callsService() throws Exception {
        UserAccount admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(admin));

        NotificationDto dto = new NotificationDto(
                UUID.randomUUID(), "Hi", "<p><strong>hello</strong></p>",
                Severity.INFO, null, null, null, Instant.now(), false);
        when(service.create(any(), any())).thenReturn(dto);

        mvc.perform(post("/api/admin/notifications")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "title", "Hi",
                                "body", "**hello**",
                                "severity", "INFO"))))
                .andExpect(status().isCreated());

        verify(service).create(any(), any());
    }

    @Test
    void delete_removesNotification() throws Exception {
        UUID id = UUID.randomUUID();
        Notification existing = new Notification();
        existing.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        mvc.perform(delete("/api/admin/notifications/{id}", id)
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(repo).delete(existing);
    }

    @Test
    void list_returnsAdminDtosNewestFirstWithoutTheEntity() throws Exception {
        UUID target = UUID.randomUUID();
        AdminNotificationDto targeted = new AdminNotificationDto(UUID.randomUUID(), "T", "**b**", "<p><strong>b</strong></p>",
                Severity.INFO, null, null, null, Instant.now(), "TARGETED", target, "someone");
        AdminNotificationDto broadcast = new AdminNotificationDto(UUID.randomUUID(), "B", "b", "<p>b</p>",
                Severity.WARNING, null, null, null, Instant.now().minusSeconds(60), "BROADCAST", null, null);
        when(service.adminPage(any())).thenReturn(new PageImpl<>(List.of(targeted, broadcast)));

        mvc.perform(get("/api/admin/notifications")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].audience").value("TARGETED"))
                .andExpect(jsonPath("$.content[0].targetUserId").value(target.toString()))
                .andExpect(jsonPath("$.content[0].targetLogin").value("someone"))
                .andExpect(jsonPath("$.content[0].bodyHtml").value("<p><strong>b</strong></p>"))
                .andExpect(jsonPath("$.content[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.content[1].audience").value("BROADCAST"))
                .andExpect(jsonPath("$.content[1].targetLogin").doesNotExist());
    }

    @Test
    void preview_returnsTheRenderedBody() throws Exception {
        when(service.preview("**hi** <script>x</script>"))
                .thenReturn(new NotificationPreviewDto("<p><strong>hi</strong> </p>"));

        mvc.perform(post("/api/admin/notifications/preview")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("body", "**hi** <script>x</script>"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyHtml").value(org.hamcrest.Matchers.containsString("<strong>hi</strong>")))
                .andExpect(jsonPath("$.bodyHtml").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script"))));
    }
}
