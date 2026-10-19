package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.api.ApiMetaTagsController;
import fr.enimaloc.catapult.api.ApiWellKnownController;
import fr.enimaloc.catapult.api.admin.ApiAdminMetaTagsController;
import fr.enimaloc.catapult.api.admin.ApiAdminWellKnownController;
import fr.enimaloc.catapult.service.meta.MetaTagService;
import fr.enimaloc.catapult.service.wellknown.IanaWellKnownRegistry;
import fr.enimaloc.catapult.service.wellknown.SchemaStoreRegistry;
import fr.enimaloc.catapult.service.wellknown.WellKnownFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The non-dev chain of ApiSecurityConfig, really active: the public serve and meta-tags routes vs the admin ones. */
@WebMvcTest(
        controllers = {ApiWellKnownController.class, ApiAdminWellKnownController.class, ApiMetaTagsController.class, ApiAdminMetaTagsController.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, ApiSecurityConfigWellKnownTest.Keys.class})
@ActiveProfiles("test")
class ApiSecurityConfigWellKnownTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class Keys {
        @org.springframework.context.annotation.Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean WellKnownFileService service;
    @MockitoBean IanaWellKnownRegistry registry;
    @MockitoBean SchemaStoreRegistry schemas;
    @MockitoBean MetaTagService metaTags;

    @Test
    void theServeRouteIsPublic() throws Exception {
        when(service.find(any(), any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/well-known/serve").param("location", "WELL_KNOWN").param("path", "x.txt")
                        .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAdminRoutesRefuseAnAnonymousVisitor() throws Exception {
        mvc.perform(get("/api/admin/well-known").with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theMetaTagsAreReadPublicly_butAdministeredByAnAdminOnly() throws Exception {
        when(metaTags.list()).thenReturn(java.util.List.of());
        mvc.perform(get("/api/meta-tags").with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/meta-tags").with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }))
                .andExpect(status().isUnauthorized());
    }
}
