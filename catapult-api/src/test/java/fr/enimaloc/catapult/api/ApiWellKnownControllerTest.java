package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.wellknown.WellKnownFile;
import fr.enimaloc.catapult.service.wellknown.WellKnownFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ApiWellKnownController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class ApiWellKnownControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean WellKnownFileService service;

    @Test
    void servesTheContentWithItsType() throws Exception {
        WellKnownFile f = new WellKnownFile();
        f.setLocation(WELL_KNOWN);
        f.setPath("acme-challenge/abc");
        f.setContentType("application/json");
        f.setContent("{\"a\":1}");
        when(service.find(WELL_KNOWN, "acme-challenge/abc")).thenReturn(Optional.of(f));

        mvc.perform(get("/api/well-known/serve").param("location", "WELL_KNOWN").param("path", "acme-challenge/abc"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(content().string("{\"a\":1}"));
    }

    @Test
    void answers404WhenAbsentOrUnreadable() throws Exception {
        when(service.find(any(), any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/well-known/serve").param("location", "ROOT").param("path", "riot.txt"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/well-known/serve").param("location", "NOPE").param("path", "riot.txt"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/well-known/serve").param("location", "ROOT"))
                .andExpect(status().isNotFound());
    }
}
