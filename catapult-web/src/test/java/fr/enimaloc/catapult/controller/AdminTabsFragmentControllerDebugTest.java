package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.DebugExchangeFilter;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The members menu's debug items and a debugged member's exchanges page (#members/{id}/debug). */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminTabsFragmentControllerDebugTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EXCHANGE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean BroadcastState broadcastState;

    @BeforeEach
    void admin() {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.currentUserId()).thenReturn(ADMIN);
    }

    private String fetch(String url, Locale locale) throws Exception {
        return mvc.perform(get(url).locale(locale).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andReturn().getResponse().getContentAsString();
    }

    private String members(MemberDebugStateDto debug, Locale locale) throws Exception {
        when(apiService.adminMembers("", 0)).thenReturn(new AdminMembersPage(List.of(
                new AdminMemberDto(ID, "2", "viewer", "img", null, false, true, "ACTIVE", NOW, debug)),
                Map.of(), false, 0, 1, 1));
        return fetch("/spa/admin/members", locale);
    }

    private static MemberDebugStateDto state(boolean active, boolean reveal) {
        return new MemberDebugStateDto(active, reveal, NOW.minusSeconds(7200), ADMIN, active ? null : NOW,
                active ? null : NOW.plusSeconds(30 * 86400L), 4);
    }

    @Test
    void menu_neverDebugged_offersToTurnItOnOnly() throws Exception {
        String html = members(null, Locale.ENGLISH);

        assertThat(html).contains("data-action=\"debug-enable\"", "Turn on debug mode", "id=\"admin-debug-dialog\"",
                        "admin-debug-reveal", "Tokens and keys will be recorded in clear")
                .doesNotContain("debug/disable", "debug/clear", "Turn off debug mode", "View the recording",
                        "Delete the recording", "admin-member-debug-badge");
    }

    @Test
    void menu_active_offersToTurnItOff_view_delete_andBadgesTheRow() throws Exception {
        String html = members(state(true, false), Locale.ENGLISH);

        assertThat(html).contains("data-action=\"debug/disable\"", "Turn off debug mode", "View the recording",
                        "data-admin-open=\"" + ID + "/debug\"", "data-action=\"debug/clear\"", "Delete the recording",
                        "data-confirm=\"Delete everything recorded for &quot;viewer&quot;?", "admin-member-debug-badge")
                .doesNotContain("data-action=\"debug-enable\"");
    }

    @Test
    void menu_disabledWithKeptRecording_offersTurnOnAgain_view_delete_butNoBadge() throws Exception {
        String html = members(state(false, false), Locale.FRENCH);

        assertThat(html).contains("data-action=\"debug-enable\"", "Activer le mode débug", "Voir l&#39;enregistrement",
                        "Supprimer l&#39;enregistrement", "data-action=\"debug/clear\"")
                .doesNotContain("data-action=\"debug/disable\"", "admin-member-debug-badge");
    }

    private void stubPage() {
        when(apiService.adminMember(ID)).thenReturn(new AdminMemberSummaryDto(ID, "viewer"));
        when(apiService.memberDebug(ID)).thenReturn(state(true, false));
        when(apiService.memberDebugServices(ID)).thenReturn(List.of("steam", "twitch"));
        when(apiService.memberDebugExchanges(any(), any(), anyInt())).thenReturn(new AdminPage<>(List.of(
                new DebugExchangeSummaryDto(EXCHANGE, NOW, "OUT", "steam", "getOwnedGames", "GET",
                        "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1?key=[masked]&steamid=7656119800000000000000000000000000000",
                        500, true, 1203, false),
                new DebugExchangeSummaryDto(UUID.randomUUID(), NOW, "IN", "twitch", "eventsub", "POST",
                        "https://catapult.example/cb", 204, false, 12, true)), 1, 3, 11));
    }

    @Test
    void page_rendersTheHeaderTheFiltersTheRowsAndThePagination() throws Exception {
        stubPage();

        String html = fetch("/spa/admin/members/" + ID + "/debug?service=steam&direction=OUT&outcome=error&page=1",
                Locale.ENGLISH);

        assertThat(html).contains("viewer", "data-debug-member=\"" + ID + "\"", "masked", "data-debug-count=\"4\"",
                "<mdui-menu-item value=\"steam\">steam</mdui-menu-item>", "<mdui-menu-item value=\"twitch\">twitch</mdui-menu-item>",
                "data-debug-exchange=\"" + EXCHANGE + "\"", "getOwnedGames", "1203", "error", "filtered",
                "data-debug-page=\"0\"", "data-debug-page=\"2\"", "2 / 3", "data-admin-back", "data-debug-clear")
                .doesNotContain("data-admin-page=", "7656119800000000000000000000000000000");
        assertThat(html).containsPattern("data-debug-filter=\"service\"[^>]*value=\"steam\"")
                .containsPattern("data-debug-filter=\"direction\"[^>]*value=\"OUT\"")
                .containsPattern("data-debug-filter=\"outcome\"[^>]*value=\"error\"");
        verify(apiService).memberDebugExchanges(ID, new DebugExchangeFilter("steam", "OUT", "error", null, null), 1);
    }

    @Test
    void page_passesThePeriodThrough_andIgnoresAnUnreadableDate() throws Exception {
        stubPage();

        String html = fetch("/spa/admin/members/" + ID + "/debug?from=2026-10-24T08:00:00.000Z&to=not-a-date", Locale.ENGLISH);

        verify(apiService).memberDebugExchanges(ID,
                new DebugExchangeFilter(null, null, null, Instant.parse("2026-10-24T08:00:00Z"), null), 0);
        assertThat(html).contains("data-iso=\"2026-10-24T08:00:00Z\"").doesNotContain("not-a-date");
        // no filter: each select shows its "all" option, which mdui-select can only select by a non-blank value
        assertThat(html).containsPattern("data-debug-filter=\"service\"[^>]*value=\"all\"")
                .containsPattern("data-debug-filter=\"direction\"[^>]*value=\"all\"")
                .containsPattern("data-debug-filter=\"outcome\"[^>]*value=\"all\"")
                .doesNotContain("<mdui-menu-item value=\"\"");
    }

    @Test
    void page_aMemberNeverDebugged_isMissing_andNoApiAnswerIsUnavailable() throws Exception {
        when(apiService.adminMember(ID)).thenReturn(null);
        mvc.perform(get("/spa/admin/members/" + ID + "/debug").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());

        when(apiService.adminMember(ID)).thenReturn(new AdminMemberSummaryDto(ID, "viewer"));
        when(apiService.memberDebug(ID)).thenReturn(null);
        String html = fetch("/spa/admin/members/" + ID + "/debug", Locale.ENGLISH);
        assertThat(html).contains("data-admin-retry").doesNotContain("data-debug-exchange");
        verify(apiService, never()).memberDebugExchanges(any(), any(), anyInt());
    }

    @Test
    void page_isRefusedToNonAdmins() throws Exception {
        when(apiService.isAdmin()).thenReturn(false);

        mvc.perform(get("/spa/admin/members/" + ID + "/debug").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isForbidden());
        verify(apiService, never()).memberDebugExchanges(any(), any(), anyInt());
    }

    @Test
    void exchange_isServedAsJson_or404() throws Exception {
        when(apiService.memberDebugExchange(ID, EXCHANGE)).thenReturn(new DebugExchangeDto(EXCHANGE, NOW, "OUT", "steam",
                "getOwnedGames", "GET", "https://x", 500, true, 12, false, "{\"A\":\"b\"}", null, "{}", "<html/>", "boom", "abc123"));

        mvc.perform(get("/spa/admin/members/" + ID + "/debug/" + EXCHANGE).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.traceId").value("abc123"))
                .andExpect(jsonPath("$.responseBody").value("<html/>"));
        mvc.perform(get("/spa/admin/members/" + ID + "/debug/" + UUID.randomUUID()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());
    }
}
