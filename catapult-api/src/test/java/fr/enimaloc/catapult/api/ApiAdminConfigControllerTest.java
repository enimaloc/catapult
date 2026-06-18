package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.ConfigAudit;
import fr.enimaloc.catapult.domain.ConfigOverride;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ConfigAuditRepository;
import fr.enimaloc.catapult.repository.ConfigOverrideRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.config.ConfigCatalogService;
import fr.enimaloc.catapult.service.config.ConfigEntry;
import fr.enimaloc.catapult.service.config.ConfigOverrideService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ApiAdminConfigController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiAdminConfigControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean ConfigCatalogService catalogService;
    @MockitoBean ConfigOverrideService overrideService;
    @MockitoBean UserAccountRepository userRepo;
    @MockitoBean ConfigAuditRepository auditRepo;
    @MockitoBean ConfigOverrideRepository overrideRepo;

    @Test
    @WithMockUser(roles = "ADMIN")
    void getCatalog_returnsEntries() throws Exception {
        when(catalogService.catalog()).thenReturn(List.of(
                new ConfigEntry("app.foo", "1", false, false, false, false, "properties")
        ));
        mvc.perform(get("/api/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("app.foo"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void putValue_callsApply() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));

        mvc.perform(put("/api/admin/config/{k}", "app.foo")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "42"))))
                .andExpect(status().isNoContent());

        verify(overrideService).apply(eq("app.foo"), eq("42"), any(UserAccount.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteValue_callsClear() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));

        mvc.perform(delete("/api/admin/config/{k}", "app.foo")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(overrideService).clear(eq("app.foo"), any(UserAccount.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void audit_returnsHistory() throws Exception {
        when(auditRepo.findByKeyOrderByChangedAtDesc("app.foo")).thenReturn(List.of(new ConfigAudit()));
        mvc.perform(get("/api/admin/config/audit").param("key", "app.foo"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticated_returns401Or403() throws Exception {
        mvc.perform(get("/api/admin/config"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getModuleOverrides_returnsList() throws Exception {
        ConfigOverride o = new ConfigOverride();
        o.setModule("web");
        o.setKey("web.feature.foo");
        o.setValue("true");
        o.setSecret(false);
        o.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        o.setUpdatedBy(UUID.randomUUID());
        when(overrideRepo.findByIdModule("web")).thenReturn(List.of(o));

        mvc.perform(get("/api/admin/config/module/{module}", "web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("web.feature.foo"))
                .andExpect(jsonPath("$[0].value").value("true"))
                .andExpect(jsonPath("$[0].secret").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void putValueForModule_callsApplyWithModule() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));

        mvc.perform(put("/api/admin/config/module/{m}/{k}", "web", "web.feature.foo")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "true"))))
                .andExpect(status().isNoContent());

        verify(overrideService).apply(eq("web"), eq("web.feature.foo"), eq("true"), any(UserAccount.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteValueForModule_callsClearWithModule() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));

        mvc.perform(delete("/api/admin/config/module/{m}/{k}", "web", "web.feature.foo")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(overrideService).clear(eq("web"), eq("web.feature.foo"), any(UserAccount.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void moduleAudit_returnsHistoryScopedToModule() throws Exception {
        when(auditRepo.findByModuleAndKeyOrderByChangedAtDesc("web", "web.feature.foo"))
                .thenReturn(List.of(new ConfigAudit()));
        mvc.perform(get("/api/admin/config/module/{m}/audit", "web").param("key", "web.feature.foo"))
                .andExpect(status().isOk());
    }
}
