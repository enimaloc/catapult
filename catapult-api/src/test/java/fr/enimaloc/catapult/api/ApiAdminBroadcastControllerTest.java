package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.notification.BroadcastRateLimiter;
import fr.enimaloc.catapult.service.notification.BroadcastValidator;
import fr.enimaloc.catapult.service.notification.RedisEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
// Used by tests that assert the publisher is NOT touched on validation failures.
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminBroadcastController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({BroadcastValidator.class, BroadcastRateLimiter.class})
class ApiAdminBroadcastControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    @MockitoBean RedisEventPublisher publisher;
    @MockitoBean ActivityLogService activityLog;
    @MockitoBean UserAccountRepository userRepo;

    UserAccount admin;

    @BeforeEach
    void setUp() {
        admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(admin));
    }

    // Non-admin access is enforced by the path matcher in ApiSecurityConfig
    // (.requestMatchers("/api/admin/**").hasRole("ADMIN")), which lives outside
    // the @WebMvcTest slice. The @PreAuthorize annotation on the controller is
    // a defence-in-depth marker; covered by the broader security integration tests.

    @Test
    void valid_alert_info_returns_202_and_publishes_on_global_channel() throws Exception {
        mvc.perform(post("/api/admin/broadcast")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", "alert.info",
                                "title", "hello",
                                "body", "world",
                                "ttlSeconds", 60))))
                .andExpect(status().isAccepted());

        verify(publisher).publishGlobal(eq("alert.info"), any());
        verify(activityLog).addEntry(eq(admin.getId()), eq("INFO"),
                org.mockito.ArgumentMatchers.contains("alert.info"));
    }

    @Test
    void valid_maintenance_scheduled_publishes() throws Exception {
        mvc.perform(post("/api/admin/broadcast")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"maintenance.scheduled",
                                 "startsAt":"2026-06-22T20:00:00Z",
                                 "durationMinutes":15,
                                 "message":"deploying"}"""))
                .andExpect(status().isAccepted());

        verify(publisher).publishGlobal(eq("maintenance.scheduled"), any());
    }

    @Test
    void reserved_lifecycle_name_returns_400() throws Exception {
        // Jackson rejects unknown subtypes with 400 (no matching @JsonSubTypes entry).
        mvc.perform(post("/api/admin/broadcast")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"maintenance.imminent",
                                 "reason":"shutdown","etaSeconds":10}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }

    @Test
    void unknown_name_returns_400() throws Exception {
        mvc.perform(post("/api/admin/broadcast")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"party.time","dj":"kraftwerk"}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }

    @Test
    void missing_required_field_returns_400() throws Exception {
        // alert.info requires title/body/ttlSeconds — omit `body` to trigger @Valid.
        mvc.perform(post("/api/admin/broadcast")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", "alert.info",
                                "title", "hi",
                                "ttlSeconds", 60))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }

    @Test
    void eleventh_broadcast_in_a_minute_is_rate_limited() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "name", "alert.info", "title", "t", "body", "b", "ttlSeconds", 1));

        for (int i = 0; i < BroadcastRateLimiter.PER_MINUTE; i++) {
            mvc.perform(post("/api/admin/broadcast")
                            .with(adminJwt())
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(post("/api/admin/broadcast")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.claim("twitchId", "123"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
