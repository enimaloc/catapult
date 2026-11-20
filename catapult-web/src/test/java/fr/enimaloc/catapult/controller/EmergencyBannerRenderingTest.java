package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.degraded.DegradedLabels;
import fr.enimaloc.catapult.service.emergency.EmergencyState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The global emergency banner as the shell renders it (fragments/emergency.html). */
@WebMvcTest(controllers = IndexController.class)
@Import({ModelFiller.class, DegradedLabels.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class EmergencyBannerRenderingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;
    @MockitoBean EmergencyState emergencyState;

    private static EmergencyStopDto stop(String provider, Instant expiresAt) {
        return new EmergencyStopDto(UUID.randomUUID(), provider, "GLOBAL", expiresAt == null ? "OWNER" : "THRESHOLD",
                null, Instant.parse("2026-11-19T10:00:00Z"), expiresAt, null, null);
    }

    private String render(List<EmergencyStopDto> stops) throws Exception {
        when(emergencyState.globalStops()).thenReturn(stops);
        return mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void noGlobalStop_noBanner() throws Exception {
        assertThat(render(List.of())).doesNotContain("emergency-banner");
    }

    @Test
    void aTimedStop_namesTheProviderAndWhenItResumes() throws Exception {
        String html = render(List.of(stop("XBOX", Instant.parse("2026-11-20T12:00:00Z"))));

        assertThat(html).contains("id=\"emergency-banner\"").contains("Detection of Xbox suspended")
                .contains("resumes on 11/20/2026");
    }

    @Test
    void anOwnerStop_isUntilFurtherNotice() throws Exception {
        String html = render(List.of(stop("ALL", null)));

        assertThat(html).contains("Detection of all providers suspended").contains("until further notice");
    }

    @Test
    void severalStops_areListedInARow() throws Exception {
        String html = render(List.of(stop("XBOX", Instant.parse("2026-11-20T12:00:00Z")), stop("STEAM", null)));

        assertThat(html).contains("Detection of Xbox suspended").contains("Detection of Steam suspended");
    }
}
