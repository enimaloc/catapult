package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.IanaRegistryDto;
import fr.enimaloc.catapult.common.dto.admin.IanaWellKnownDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.service.wellknown.IanaWellKnownRegistry;
import fr.enimaloc.catapult.service.wellknown.WellKnownFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminWellKnownController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false) // the admin guard is ApiSecurityConfig's, tested with it
class ApiAdminWellKnownControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean WellKnownFileService service;
    @MockitoBean IanaWellKnownRegistry registry;

    static final String BODY = "{\"location\":\"WELL_KNOWN\",\"path\":\"security.txt\",\"contentType\":\"text/plain\",\"content\":\"x\"}";
    static final WellKnownFileDetailDto DETAIL = new WellKnownFileDetailDto(3, WELL_KNOWN, "security.txt", "text/plain", "x",
            Instant.parse("2026-10-16T10:00:00Z"));

    @Test
    void createAnswers201WithTheFile() throws Exception {
        when(service.create(any())).thenReturn(DETAIL);
        mvc.perform(post("/api/admin/well-known").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.location").value("WELL_KNOWN"));
        verify(service).create(new WellKnownFileRequest(WELL_KNOWN, "security.txt", "text/plain", "x"));
    }

    @Test
    void updateGetDelete() throws Exception {
        when(service.update(eq(3L), any())).thenReturn(DETAIL);
        when(service.get(3L)).thenReturn(DETAIL);
        mvc.perform(put("/api/admin/well-known/3").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/well-known/3")).andExpect(jsonPath("$.content").value("x"));
        mvc.perform(delete("/api/admin/well-known/3")).andExpect(status().isNoContent());
        verify(service).delete(3L);
    }

    @Test
    void relaysTheServicesRefusals() throws Exception {
        when(service.create(any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        mvc.perform(post("/api/admin/well-known").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void ianaAnswersTheRegistry() throws Exception {
        when(registry.registry()).thenReturn(new IanaRegistryDto(
                java.util.List.of(new IanaWellKnownDto("security.txt", "RFC 9116", "permanent", "IETF")), "LIVE"));
        mvc.perform(get("/api/admin/well-known/iana"))
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.entries[0].suffix").value("security.txt"));
    }
}
