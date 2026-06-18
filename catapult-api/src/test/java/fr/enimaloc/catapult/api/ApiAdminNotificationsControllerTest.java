package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.Notification;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.NotificationRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.NotificationDto;
import fr.enimaloc.catapult.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminNotificationsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiAdminNotificationsControllerTest {

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
                Notification.Severity.INFO, null, null, null, Instant.now(), false);
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
}
