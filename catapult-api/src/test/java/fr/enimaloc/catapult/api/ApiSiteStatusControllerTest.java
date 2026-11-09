package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.site.SiteRole;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ApiSiteStatusController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class ApiSiteStatusControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SiteRole siteRole;

    @Test
    void siteStatus_reportsActiveAndReason() throws Exception {
        when(siteRole.status()).thenReturn(new SiteRole.SiteStatus(true, true, "dns points here", Instant.parse("2026-11-03T10:00:00Z")));

        mvc.perform(get("/api/internal/status/site"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.reason").value("dns points here"));
    }
}
