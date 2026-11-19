package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.emergency.EmergencyStatusDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.service.emergency.EmergencyViews;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = EmergencyStatusController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class EmergencyStatusControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EmergencyViews views;

    @Test
    void answersTheGlobalStopsWithoutAnyCaller() throws Exception {
        when(views.status()).thenReturn(new EmergencyStatusDto(List.of(new EmergencyStopDto(UUID.randomUUID(),
                "STEAM", "GLOBAL", "THRESHOLD", null, Instant.parse("2026-11-19T10:00:00Z"),
                Instant.parse("2026-11-20T10:00:00Z"), null, null))));

        mvc.perform(get("/api/internal/emergency/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalStops[0].provider").value("STEAM"))
                .andExpect(jsonPath("$.globalStops[0].scope").value("GLOBAL"));
    }
}
