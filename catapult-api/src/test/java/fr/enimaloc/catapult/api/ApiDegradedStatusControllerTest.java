package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.DegradedEffectDto;
import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.service.status.DegradedStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ApiDegradedStatusController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class ApiDegradedStatusControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DegradedStatusService service;

    @Test
    void answersTheStatus_withItsEffects() throws Exception {
        when(service.status()).thenReturn(new DegradedStatusDto(true,
                List.of(new DegradedEffectDto("app.polling.interval-seconds", "60", "30"))));

        mvc.perform(get("/api/status/degraded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.effects[0].key").value("app.polling.interval-seconds"))
                .andExpect(jsonPath("$.effects[0].value").value("60"))
                .andExpect(jsonPath("$.effects[0].normalValue").value("30"));
    }

    @Test
    void notDegraded_leavesTheEffectsOut() throws Exception {
        when(service.status()).thenReturn(new DegradedStatusDto(false, null));

        mvc.perform(get("/api/status/degraded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.effects").doesNotExist());
    }
}
