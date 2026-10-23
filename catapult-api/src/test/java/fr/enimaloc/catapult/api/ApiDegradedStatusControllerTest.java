package fr.enimaloc.catapult.api;

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
    void answersTheStatus_withoutNullFields() throws Exception {
        when(service.status()).thenReturn(new DegradedStatusDto(true, 60, null));

        mvc.perform(get("/api/status/degraded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.gameDetectionIntervalSeconds").value(60))
                .andExpect(jsonPath("$.minecraftFriendsSyncMinutes").doesNotExist());
    }
}
