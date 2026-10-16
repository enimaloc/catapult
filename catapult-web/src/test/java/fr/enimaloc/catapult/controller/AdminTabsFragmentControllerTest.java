package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminCclDto;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminIgdbDescriptorDto;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin tabs' lazy fragments: rendered for an admin only, from what catapult-api answers. */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminTabsFragmentControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    private String asAdmin(String url) throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        return mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyFragment_isRefusedToAMember_andToAVisitor() throws Exception {
        for (String url : List.of("/spa/admin/ccl", "/spa/admin/caches", "/spa/admin/caches/igdb-game-cache",
                "/spa/admin/caches/igdb-game-cache/entry?key=1",
                "/spa/admin/data", "/spa/admin/data/chat-command", "/spa/admin/data/chat-command/row?id=1",
                "/spa/admin/well-known", "/spa/admin/well-known/file?id=1")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isForbidden());
        }
        verify(apiService, org.mockito.Mockito.atLeastOnce()).isAdmin();
        verify(apiService, never()).adminCcl();
        verify(apiService, never()).adminCaches();
        verify(apiService, never()).adminCacheEntries(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(apiService, never()).adminCacheEntry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(apiService, never()).adminDataRepos();
        verify(apiService, never()).adminDataRows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(apiService, never()).adminDataRow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(apiService, never()).adminWellKnownFiles();
        verify(apiService, never()).adminIanaRegistry();
        verify(apiService, never()).adminWellKnownFile(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void ccl_rendersEachCclWithItsMappedDescriptors() throws Exception {
        var violence = new AdminIgdbDescriptorDto(1L, "Violence", 1, "ESRB — Violence");
        var blood = new AdminIgdbDescriptorDto(2L, "Blood", 1, "ESRB — Blood");
        when(apiService.adminCcl()).thenReturn(new AdminCclPage(
                List.of(new AdminCclDto("ViolentGraphic", "Violent", "Graphic violence", List.of(blood, violence))),
                List.of(violence, blood)));

        String html = asAdmin("/spa/admin/ccl");

        assertThat(html).contains("id=\"admin-ccl-refresh\"", "data-ccl-id=\"ViolentGraphic\"", "data-saved=\"1,2\"",
                "Graphic violence", "<mdui-menu-item value=\"1\">ESRB — Violence</mdui-menu-item>",
                "data-error=\"The action failed ({0}).\"");
    }

    @Test
    void ccl_saysSo_whenCatapultApiFails() throws Exception {
        String html = asAdmin("/spa/admin/ccl");

        assertThat(html).contains("data-admin-retry").doesNotContain("data-ccl-id");
    }

    @Test
    void ccl_invitesToRefresh_whenThereIsNone() throws Exception {
        when(apiService.adminCcl()).thenReturn(new AdminCclPage(List.of(), List.of()));

        assertThat(asAdmin("/spa/admin/ccl")).contains("id=\"admin-ccl-refresh\"", "admin-placeholder").doesNotContain("data-ccl-id");
    }

    @Test
    void caches_listsEachCacheToOpen() throws Exception {
        when(apiService.adminCaches()).thenReturn(List.of(
                new CacheSummaryDto("igdb-game-cache", 30, true), new CacheSummaryDto("tw-known-paths", 2, false)));

        String html = asAdmin("/spa/admin/caches");

        assertThat(html).contains("data-admin-open=\"igdb-game-cache\"", "data-admin-open=\"tw-known-paths\"");
    }

    @Test
    void cacheEntries_listsAPage_withDeleteOnlyForADeletableCache() throws Exception {
        when(apiService.adminCaches()).thenReturn(List.of(new CacheSummaryDto("tw-known-paths", 2, false)));
        when(apiService.adminCacheEntries("tw-known-paths", "game", 1)).thenReturn(
                new AdminPage<>(List.of(new CacheEntryDto("game.name", "string")), 1, 3, 51));

        String html = asAdmin("/spa/admin/caches/tw-known-paths?q=game&page=1");

        assertThat(html).contains("data-admin-results", "data-key=\"game.name\"", "admin-cache-detail",
                        "data-admin-page=\"0\"", "data-admin-page=\"2\"", "2 / 3", "value=\"game\"")
                .doesNotContain("admin-cache-delete");
    }

    @Test
    void cacheEntries_offersEviction_forADeletableCache() throws Exception {
        when(apiService.adminCaches()).thenReturn(List.of(new CacheSummaryDto("igdb-game-cache", 30, true)));
        when(apiService.adminCacheEntries("igdb-game-cache", null, 0)).thenReturn(
                new AdminPage<>(List.of(new CacheEntryDto("1001", "string")), 0, 1, 1));

        String html = asAdmin("/spa/admin/caches/igdb-game-cache");

        assertThat(html).contains("admin-cache-delete", "data-key=\"1001\"", "data-confirm=\"", "1001");
        assertThat(html).containsPattern("data-confirm=\"[^\"]*1001[^\"]*\"");
    }

    @Test
    void cacheEntries_saysNothingMatches_whenASearchFindsNothing() throws Exception {
        when(apiService.adminCaches()).thenReturn(List.of(new CacheSummaryDto("igdb-game-cache", 30, true)));
        when(apiService.adminCacheEntries("igdb-game-cache", "zzz", 0)).thenReturn(new AdminPage<>(List.of(), 0, 0, 0));

        String html = asAdmin("/spa/admin/caches/igdb-game-cache?q=zzz");

        assertThat(html).contains("data-admin-results", "admin-placeholder").doesNotContain("admin-pagination");
    }

    @Test
    void cacheEntry_showsTheRecordBehindIt() throws Exception {
        when(apiService.adminCacheEntry("igdb-game-cache", "a b")).thenReturn(
                new CacheEntryDetailDto("a b", Map.of("name", "<b>Doom</b>"), 120L));

        String html = asAdmin("/spa/admin/caches/igdb-game-cache/entry?key=a b");

        assertThat(html).contains("&lt;b&gt;Doom&lt;/b&gt;", "Expires in 120 s").doesNotContain("<b>Doom</b>");
    }

    @Test
    void cacheEntry_printsItsExpiryWithoutDigitGrouping() throws Exception {
        when(apiService.adminCacheEntry("igdb-game-cache", "soon")).thenReturn(new CacheEntryDetailDto("soon", Map.of(), 3600L));
        when(apiService.adminCacheEntry("igdb-game-cache", "gone")).thenReturn(new CacheEntryDetailDto("gone", Map.of(), -12345L));

        assertThat(asAdmin("/spa/admin/caches/igdb-game-cache/entry?key=soon")).contains("Expires in 3600 s");
        assertThat(asAdmin("/spa/admin/caches/igdb-game-cache/entry?key=gone")).contains("Expired 12345 s ago");
        assertThat(mvc.perform(get("/spa/admin/caches/igdb-game-cache/entry?key=soon").locale(java.util.Locale.FRENCH)
                        .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andReturn().getResponse().getContentAsString()).contains("Expire dans 3600 s");
    }

    @Test
    void data_listsEachRepositoryToOpen() throws Exception {
        when(apiService.adminDataRepos()).thenReturn(List.of(new RepoSummaryDto("chat-command", 3)));

        assertThat(asAdmin("/spa/admin/data")).contains("data-admin-open=\"chat-command\"");
    }

    @Test
    void dataRows_listsAPageOfRows_eachOpeningItsDialog() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "a~b%20c");
        row.put("user", "42");
        row.put("user_label", "enimaloc");
        when(apiService.adminDataRows("chat-command", null, 0)).thenReturn(new AdminPage<>(List.of(row), 0, 1, 1));

        String html = asAdmin("/spa/admin/data/chat-command");

        assertThat(html).contains("data-repo=\"chat-command\"", "id=\"admin-data-new\"", "data-admin-results",
                "class=\"admin-row admin-data-row\"", "data-id=\"a~b%20c\"", "42 · enimaloc")
                .doesNotContain(">user_label<");
    }

    @Test
    void dataRow_editsAnExistingRow() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "1");
        row.put("name", "!lurk");
        row.put("cooldown", null);
        row.put("aliases", List.of(Map.of("id", "7", "label", "!l")));
        when(apiService.adminDataRow("chat-command", "1")).thenReturn(row);

        String html = asAdmin("/spa/admin/data/chat-command/row?id=1");

        assertThat(html).contains("data-id=\"1\"", "name=\"name\"", "value=\"!lurk\"", "!l", "admin-data-delete")
                .doesNotContain("name=\"id\"");
        // Each field keeps the value it was rendered with, for admin-data.js to send only the changed
        // ones; a null one shows blank, without value nor data-initial (th:attr drops a blank one).
        assertThat(html).contains("value=\"!lurk\" data-initial=\"!lurk\"",
                "<mdui-text-field variant=\"outlined\" name=\"cooldown\" label=\"cooldown\"></mdui-text-field>");
    }

    @Test
    void dataRow_isNotFound_whenTheRowIsGone() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDataRow("chat-command", "9")).thenReturn(null); // a mock otherwise answers an empty map

        mvc.perform(get("/spa/admin/data/chat-command/row?id=9").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dataRow_withoutAnId_isANewRowLaidOutOnTheFirstOne() throws Exception {
        when(apiService.adminDataRows("chat-command", null, 0)).thenReturn(
                new AdminPage<>(List.of(Map.of("id", "1", "name", "!lurk")), 0, 1, 1));

        String html = asAdmin("/spa/admin/data/chat-command/row");

        assertThat(html).contains("name=\"id\"", "name=\"name\"").doesNotContain("!lurk", "admin-data-delete", "data-id=");
    }

    private static fr.enimaloc.catapult.common.dto.admin.IanaRegistryDto registry(String source) {
        return new fr.enimaloc.catapult.common.dto.admin.IanaRegistryDto(List.of(
                new fr.enimaloc.catapult.common.dto.admin.IanaWellKnownDto("security.txt", "RFC9116", "permanent", "IETF"),
                new fr.enimaloc.catapult.common.dto.admin.IanaWellKnownDto("Host-Meta", "https://example.org/hm", "permanent", "IETF")), source);
    }

    @Test
    void wellKnown_rendersTheFilesAndTheRegistry() throws Exception {
        when(apiService.adminWellKnownFiles()).thenReturn(List.of(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto(
                3, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "security.txt", 200, "text/plain", 12,
                java.time.Instant.parse("2025-03-01T12:00:00Z"))));
        when(apiService.adminIanaRegistry()).thenReturn(registry("LIVE"));

        String html = asAdmin("/spa/admin/well-known");

        assertThat(html).contains("admin-well-known", "id=\"admin-well-known-new\"", "data-id=\"3\"", ">/.well-known/security.txt</a>",
                        "admin-well-known-edit", "admin-well-known-delete", "data-confirm=\"Delete /.well-known/security.txt?",
                        "data-search=\"security.txt rfc9116\"", "data-search=\"host-meta https://example.org/hm\"",
                        "data-path=\"security.txt\"", "id=\"admin-well-known-dialog\"", "data-well-known-filter",
                        "data-error-400=\"", "data-error-too-large=\"", "Configured")
                .doesNotContain("offline copy");
    }

    @Test
    void wellKnown_notesAnOfflineRegistry() throws Exception {
        when(apiService.adminWellKnownFiles()).thenReturn(List.of());
        when(apiService.adminIanaRegistry()).thenReturn(registry("SNAPSHOT"));

        assertThat(asAdmin("/spa/admin/well-known")).contains("offline copy", "No file configured").doesNotContain("Configured<");
    }

    @Test
    void wellKnown_saysWhenTheApiOrTheRegistryIsUnavailable() throws Exception {
        when(apiService.adminWellKnownFiles()).thenReturn(null);
        when(apiService.adminIanaRegistry()).thenReturn(null);

        assertThat(asAdmin("/spa/admin/well-known")).contains("admin-placeholder", "IANA registry unavailable")
                .doesNotContain("admin-well-known-file");
    }

    @Test
    void wellKnownFile_unknownId_is404WithTheMissingNote() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminWellKnownFile(9)).thenReturn(null);

        String html = mvc.perform(get("/spa/admin/well-known/file?id=9").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("This file no longer exists.").doesNotContain("admin-well-known-form");
    }

    @Test
    void wellKnownFile_newFromTheRegistry_isPrefilled() throws Exception {
        String html = asAdmin("/spa/admin/well-known/file?location=WELL_KNOWN&path=assetlinks.json");

        assertThat(html).contains("admin-well-known-form", "name=\"path\"", "value=\"assetlinks.json\"",
                "value=\"application/json\"", "New file", "admin-well-known-save", "admin-well-known-import", "admin-dialog-close")
                .doesNotContain("data-id=", "data-manual");
    }

    @Test
    void wellKnownFile_edit_carriesTheIdAndTheManualType() throws Exception {
        when(apiService.adminWellKnownFile(4)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto(
                4, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT, "riot.txt", 200, java.util.List.of(), "text/plain", "hello", null));

        String html = asAdmin("/spa/admin/well-known/file?id=4");

        assertThat(html).contains("data-id=\"4\"", "data-manual=\"true\"", "value=\"hello\"", "value=\"ROOT\"", ">/riot.txt</");
    }
}
