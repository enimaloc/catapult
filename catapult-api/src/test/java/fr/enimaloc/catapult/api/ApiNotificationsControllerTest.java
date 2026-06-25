package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.Notification;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.NotificationDto;
import fr.enimaloc.catapult.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiNotificationsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiNotificationsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificationService service;
    @MockitoBean UserAccountRepository userRepo;

    @Test
    void getList_returnsPageForCurrentUser() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));
        Page<NotificationDto> emptyPage = new PageImpl<>(List.of());
        when(service.listForUser(eq(user.getId()), any(Pageable.class))).thenReturn(emptyPage);

        mvc.perform(get("/api/notifications")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .with(csrf()))
                .andExpect(status().isOk());

        verify(service).listForUser(eq(user.getId()), any(Pageable.class));
    }

    @Test
    void markRead_returnsNoContent() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));
        UUID notifId = UUID.randomUUID();

        mvc.perform(post("/api/notifications/{id}/read", notifId)
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).markRead(eq(user.getId()), eq(notifId));
    }

    @Test
    void snapshot_returnsFirst10AndUnreadCount() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));
        seedTenNotifications(user.getId());
        mvc.perform(get("/api/notifications/snapshot")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.unreadCount").value(3));
    }

    @Test
    void snapshot_anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/notifications/snapshot"))
                .andExpect(status().isUnauthorized());
    }

    private void seedTenNotifications(UUID userId) {
        List<NotificationDto> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(new NotificationDto(UUID.randomUUID(), "Title " + i, "<p>Body</p>",
                    Notification.Severity.INFO, null, null, null, Instant.now(), i >= 3));
        }
        when(service.findRecent(eq(userId), eq(10))).thenReturn(items);
        when(service.countUnread(eq(userId))).thenReturn(3L);
    }
}
