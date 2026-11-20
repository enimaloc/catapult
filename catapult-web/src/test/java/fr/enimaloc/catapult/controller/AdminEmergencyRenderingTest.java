package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.emergency.EmergencyReportDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin emergency tab: the stops, the reports with their VOD link and unfoldable context, the history. */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminEmergencyRenderingTest {

    private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");
    private static final String CONTEXT = """
            {"activityLog":[{"timestamp":"2026-11-19T09:58:00Z","level":"INFO","message":"Game detected"},
                            {"timestamp":"2026-11-19T09:59:00Z","level":"WARN","message":"<script>alert(1)</script>"}],
             "lastGame":{"provider":"XBOX","sourceId":"42","name":"Halo <b>Infinite</b>"},
             "lastEndedGame":null,"twitchCategory":"Just Chatting","live":true}""";
    private static final String OFFLINE = "{\"activityLog\":[],\"lastGame\":null,\"lastEndedGame\":null,"
            + "\"twitchCategory\":null,\"live\":false}";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean BroadcastState broadcastState;

    @BeforeEach
    void anAdmin() {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminEmergencyStops(true)).thenReturn(List.of());
        when(apiService.adminEmergencyStops(false)).thenReturn(List.of());
        when(apiService.adminEmergencyReports(0)).thenReturn(List.of());
    }

    private String render(String url, Locale locale) throws Exception {
        return mvc.perform(get(url).locale(locale).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static EmergencyReportDto report(String vod, String context, boolean withdrawn) {
        return new EmergencyReportDto(UUID.randomUUID(), "streamer", "XBOX", "mod_one", AT, withdrawn, vod, context);
    }

    @Test
    void aMember_isRefused() throws Exception {
        when(apiService.isAdmin()).thenReturn(false);

        mvc.perform(get("/spa/admin/emergency")).andExpect(status().isUnauthorized());
        mvc.perform(get("/spa/admin/emergency").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isForbidden());
        verify(apiService, never()).adminEmergencyStops(true);
        verify(apiService, never()).adminEmergencyReports(0);
    }

    @Test
    void aReportWithAVod_linksIt_andUnfoldsItsLogs_asText() throws Exception {
        when(apiService.adminEmergencyReports(0)).thenReturn(List.of(
                report("https://www.twitch.tv/videos/1?t=1h2m3s", CONTEXT, false)));

        String html = render("/spa/admin/emergency", Locale.FRENCH);

        assertThat(html).contains("href=\"https://www.twitch.tv/videos/1?t=1h2m3s\"", "rel=\"noopener noreferrer\"",
                "<details", "Logs et contexte", "Game detected", "Just Chatting", "Dernier jeu");
        // viewer-influenced text is escaped, never markup
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "Halo &lt;b&gt;Infinite&lt;/b&gt;")
                .doesNotContain("<script>alert(1)", "<b>Infinite");
        assertThat(render("/spa/admin/emergency", Locale.ENGLISH)).contains("Logs and context");
    }

    @Test
    void noVod_isToldApart_offLiveFromUnavailable() throws Exception {
        when(apiService.adminEmergencyReports(0)).thenReturn(List.of(report(null, OFFLINE, false),
                report(null, CONTEXT, false)));

        String html = render("/spa/admin/emergency", Locale.FRENCH);

        assertThat(html).contains("Hors live", "VOD indisponible").doesNotContain("href=\"https://www.twitch");
    }

    @Test
    void aVodThatIsNotHttps_isNeverALink() throws Exception {
        when(apiService.adminEmergencyReports(0)).thenReturn(List.of(report("javascript:alert(1)", CONTEXT, false)));

        assertThat(render("/spa/admin/emergency", Locale.FRENCH)).doesNotContain("javascript:alert").contains("VOD indisponible");
    }

    @Test
    void aMalformedContext_isTold_notAnErrorPage() throws Exception {
        when(apiService.adminEmergencyReports(0)).thenReturn(List.of(report(null, "{not json", false),
                report(null, null, true)));

        String html = render("/spa/admin/emergency", Locale.FRENCH);

        assertThat(html).contains("Contexte indisponible.", "retiré");
    }

    @Test
    void theStops_andTheHistory_areListed_withTheLiftButton() throws Exception {
        when(apiService.adminEmergencyStops(true)).thenReturn(List.of(
                new EmergencyStopDto(UUID.randomUUID(), "XBOX", "GLOBAL", "THRESHOLD", null, AT, AT.plusSeconds(3600),
                        null, null),
                new EmergencyStopDto(UUID.randomUUID(), "ALL", "GLOBAL", "OWNER", null, AT, null, null, null)));
        when(apiService.adminEmergencyStops(false)).thenReturn(List.of(
                new EmergencyStopDto(UUID.randomUUID(), "EPIC", "GLOBAL", "OWNER", null, AT, null, AT, "enimaloc")));

        String html = render("/spa/admin/emergency", Locale.FRENCH);

        assertThat(html).contains("admin-em-lift", "data-provider=\"XBOX\"", "Seuil de signalements", "Propriétaire",
                "jusqu&#39;à la reprise", "par enimaloc", "admin-em-stop-button", "Epic Games");
    }

    @Test
    void aChannelStop_hasTheLiftButtonToo_whoseConfirmationNamesTheProviderWide() throws Exception {
        when(apiService.adminEmergencyStops(true)).thenReturn(List.of(
                new EmergencyStopDto(UUID.randomUUID(), "STEAM", "CHANNEL", "CHANNEL_REPORT", "streamer", AT,
                        AT.plusSeconds(3600), null, null)));

        String html = render("/spa/admin/emergency", Locale.FRENCH);

        assertThat(html).contains("admin-em-lift", "data-provider=\"STEAM\"",
                "Lever tous les arrêts Steam (global et chaînes) ?");
    }

    @Test
    void reports_arePaged_aFullPageOffersTheNext() throws Exception {
        when(apiService.adminEmergencyReports(1)).thenReturn(IntStream.range(0, 50)
                .mapToObj(i -> report(null, OFFLINE, false)).toList());

        String html = render("/spa/admin/emergency?page=1", Locale.ENGLISH);

        assertThat(html).contains("data-admin-results", "data-admin-page=\"0\"", "data-admin-page=\"2\"");
    }

    @Test
    void catapultApiDown_isTold_perList() throws Exception {
        when(apiService.adminEmergencyStops(true)).thenReturn(null);
        when(apiService.adminEmergencyStops(false)).thenReturn(null);
        when(apiService.adminEmergencyReports(0)).thenReturn(null);

        assertThat(render("/spa/admin/emergency", Locale.ENGLISH)).contains("admin-placeholder");
    }
}
