package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
import fr.enimaloc.catapult.common.dto.admin.AdminCclDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminIgdbDescriptorDto;
import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentStatus;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentVariantDto;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    @MockitoBean BroadcastState broadcastState;

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
                "/spa/admin/members?q=a&page=1", "/spa/admin/members/search?q=a", "/spa/admin/meta", "/spa/admin/meta/file?id=1", "/spa/admin/notifications?page=1", "/spa/admin/broadcast", "/spa/admin/dtdd-mapping", "/spa/admin/game-mappings?page=1&missingPage=1", "/spa/admin/groups", "/spa/admin/whitelist", "/spa/admin/keys", "/spa/admin/tw", "/spa/admin/tw/gore",
                "/spa/admin/members", "/spa/admin/members/00000000-0000-0000-0000-000000000001",
                "/spa/admin/experiments", "/spa/admin/experiments/00000000-0000-0000-0000-000000000001",
                "/spa/admin/experiments/00000000-0000-0000-0000-000000000001/participants?variant=&q=x&page=1")) {
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
        verify(apiService, never()).adminNotifications(org.mockito.ArgumentMatchers.anyInt());
        verify(apiService, never()).adminDtddProposals(org.mockito.ArgumentMatchers.any());
        verify(apiService, never()).adminGameMappings(anyInt());
        verify(apiService, never()).adminMissingGameMappings(anyInt());
        verify(apiService, never()).adminGroups();
        verify(apiService, never()).adminWhitelist();
        verify(apiService, never()).adminApiKeys(org.mockito.ArgumentMatchers.any());
        verify(apiService, never()).adminTws();
        verify(apiService, never()).adminMinecraftAccounts();
        verify(apiService, never()).adminMembers(anyString(), anyInt());
        verify(apiService, never()).adminMember(org.mockito.ArgumentMatchers.any());
        verify(apiService, never()).adminMemberTargeting(org.mockito.ArgumentMatchers.any());
        verify(apiService, never()).adminExperiments();
        verify(apiService, never()).adminExperiment(org.mockito.ArgumentMatchers.any());
        verify(apiService, never()).adminExperimentAssignments(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
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

        assertThat(html).contains("&lt;b&gt;Doom&lt;/b&gt;", "Expires in 120s").doesNotContain("<b>Doom</b>");
    }

    @Test
    void cacheEntry_printsItsExpiryWithoutDigitGrouping() throws Exception {
        when(apiService.adminCacheEntry("igdb-game-cache", "soon")).thenReturn(new CacheEntryDetailDto("soon", Map.of(), 3600L));
        when(apiService.adminCacheEntry("igdb-game-cache", "gone")).thenReturn(new CacheEntryDetailDto("gone", Map.of(), -12345L));

        assertThat(asAdmin("/spa/admin/caches/igdb-game-cache/entry?key=soon")).contains("Expires in 3600s");
        assertThat(asAdmin("/spa/admin/caches/igdb-game-cache/entry?key=gone")).contains("Expired 12345s ago");
        assertThat(mvc.perform(get("/spa/admin/caches/igdb-game-cache/entry?key=soon").locale(java.util.Locale.FRENCH)
                        .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andReturn().getResponse().getContentAsString()).contains("Expire dans 3600s", "data-live-in=\"3600\"", "data-label-future=\"Expire dans {0}\"");
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

        String html = asAdmin("/spa/admin/meta");

        assertThat(html).contains("admin-well-known", "id=\"admin-well-known-new\"", "data-id=\"3\"", ">/.well-known/security.txt</a>",
                        "admin-well-known-edit", "admin-well-known-delete", "data-confirm=\"Delete /.well-known/security.txt?",
                        "data-search=\"security.txt rfc9116\"", "data-search=\"host-meta https://example.org/hm\"",
                        "data-path=\"security.txt\"", "id=\"admin-well-known-dialog\"", "data-well-known-filter",
                        "data-error-400=\"", "data-error-too-large=\"", "Configured")
                .doesNotContain("offline copy");
    }

    @Test
    void meta_listsTheMetaTags_withTheirFormAndErrors() throws Exception {
        when(apiService.metaTags()).thenReturn(List.of(new fr.enimaloc.catapult.common.dto.admin.MetaTagDto(
                5, fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY, "og:image", "https://x/y.png", null)));
        when(apiService.adminWellKnownFiles()).thenReturn(List.of());

        String html = asAdmin("/spa/admin/meta");

        assertThat(html).contains("class=\"admin-meta-tags\"", "Meta tags", "1 tag", "data-id=\"5\"", "data-attribute=\"PROPERTY\"",
                        "data-key=\"og:image\"", "data-content=\"https://x/y.png\"", ">property</span>", "admin-meta-tag-edit",
                        "data-confirm=\"Delete the meta tag og:image?\"", "admin-meta-tags-form", "data-label-save=\"Save\"",
                        "data-error-409=\"A meta tag of this type already has this name.\"", "Well-known files");
    }

    @Test
    void meta_saysWhenTheMetaTagsAreUnavailable_andHasNoFormThen() throws Exception {
        when(apiService.metaTags()).thenReturn(null);
        when(apiService.adminWellKnownFiles()).thenReturn(List.of());

        assertThat(asAdmin("/spa/admin/meta")).contains("class=\"admin-meta-tags\"", "Unable to load this section right now.", "data-admin-retry")
                .doesNotContain("admin-meta-tags-form", "admin-meta-tag-row");
    }

    @Test
    void wellKnown_notesAnOfflineRegistry() throws Exception {
        when(apiService.adminWellKnownFiles()).thenReturn(List.of());
        when(apiService.adminIanaRegistry()).thenReturn(registry("SNAPSHOT"));

        assertThat(asAdmin("/spa/admin/meta")).contains("offline copy", "No file configured").doesNotContain("Configured<");
    }

    @Test
    void wellKnown_saysWhenTheApiOrTheRegistryIsUnavailable() throws Exception {
        when(apiService.adminWellKnownFiles()).thenReturn(null);
        when(apiService.adminIanaRegistry()).thenReturn(null);

        assertThat(asAdmin("/spa/admin/meta")).contains("admin-placeholder", "IANA registry unavailable")
                .doesNotContain("admin-well-known-file");
    }

    @Test
    void wellKnownFile_unknownId_is404WithTheMissingNote() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminWellKnownFile(9)).thenReturn(null);

        String html = mvc.perform(get("/spa/admin/meta/file?id=9").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("This file no longer exists.").doesNotContain("admin-well-known-form");
    }

    @Test
    void wellKnownFile_newFromTheRegistry_isPrefilled() throws Exception {
        String html = asAdmin("/spa/admin/meta/file?location=WELL_KNOWN&path=assetlinks.json");

        assertThat(html).contains("admin-well-known-form", "name=\"path\"", "value=\"assetlinks.json\"",
                "value=\"application/json\"", "New file", "admin-well-known-save", "admin-well-known-import", "admin-dialog-close")
                .doesNotContain("data-id=", "data-manual");
    }

    @Test
    void wellKnownFile_edit_carriesTheIdAndTheManualType() throws Exception {
        when(apiService.adminWellKnownFile(4)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto(
                4, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT, "riot.txt", 200, java.util.List.of(), "text/plain", "hello", null));

        String html = asAdmin("/spa/admin/meta/file?id=4");

        assertThat(html).contains("data-id=\"4\"", "data-manual=\"true\"", "value=\"hello\"", "value=\"ROOT\"", ">/riot.txt</");
    }

    @Test
    void wellKnownFile_redirectFile_rendersItsStatusAndHeaders() throws Exception {
        when(apiService.adminWellKnownFile(5)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto(
                5, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "change-password", 302,
                java.util.List.of(new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Location", "/account")),
                "text/plain", "", null));

        String html = asAdmin("/spa/admin/meta/file?id=5");

        assertThat(html).contains("name=\"status\"", "value=\"302\"", "admin-well-known-header\"",
                "data-header=\"name\"", "value=\"Location\"", "value=\"/account\"", "admin-well-known-redirect",
                "admin-well-known-sections", "data-label-add=\"Add\"", "data-label-schema-more=")
                .doesNotContain("data-preset", "data-initial-status")
                .containsPattern("admin-well-known-sections[^>]*value=\"response\"");
    }

    @Test
    void wellKnownFile_okFile_hasNoHeaderRowAndOpensOnContent() throws Exception {
        when(apiService.adminWellKnownFile(6)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto(
                6, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT, "riot.txt", 200, java.util.List.of(), "text/plain", "hi", null));

        String html = asAdmin("/spa/admin/meta/file?id=6");

        assertThat(html).contains("admin-well-known-sections", "admin-well-known-mode", "admin-well-known-fields", "admin-well-known-raw")
                .containsPattern("admin-well-known-sections[^>]*value=\"content\"")
                .doesNotContain("class=\"admin-well-known-header\"");
        // new header rows are built from these labels, there being no row to copy them from
        assertThat(html).contains("data-label-header-name=\"Header\"", "data-label-header-value=\"Value\"",
                "data-label-header-remove=\"Remove this header\"");
    }

    @Test
    void wellKnownFile_changePasswordPreset_isARedirectWithAnEmptyLocation() throws Exception {
        String html = asAdmin("/spa/admin/meta/file?location=WELL_KNOWN&path=change-password");

        assertThat(html).contains("data-header=\"name\"", "value=\"Location\"")
                .containsPattern("admin-well-known-sections[^>]*value=\"response\"");
    }

    @Test
    void wellKnown_listShowsTheStatusChipOfARedirect() throws Exception {
        when(apiService.adminWellKnownFiles()).thenReturn(List.of(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto(
                3, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "change-password", 302, "text/plain", 0, null),
                new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto(
                4, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "security.txt", 200, "text/plain", 5, null)));
        when(apiService.adminIanaRegistry()).thenReturn(registry("LIVE"));

        String html = asAdmin("/spa/admin/meta");

        assertThat(html).contains(">302</mdui-chip>");
    }

    @Test
    void wellKnown_everyRowHasTheSameNumberOfCells() throws Exception {
        when(apiService.adminWellKnownFiles()).thenReturn(List.of(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto(
                3, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "change-password", 302, "text/plain", 0, null),
                new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto(
                4, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "security.txt", 200, "text/plain", 5, null)));
        when(apiService.adminIanaRegistry()).thenReturn(registry("LIVE"));

        String html = asAdmin("/spa/admin/meta");
        String list = html.substring(html.indexOf("admin-well-known-file"), html.indexOf("admin-iana-row"));
        String[] rows = list.split("admin-well-known-file\"");

        // each row: link, status cell, type, size, date, edit, delete
        assertThat(rows).hasSize(3);
        for (int i = 1; i < rows.length; i++) {
            assertThat(rows[i]).containsOnlyOnce("admin-well-known-link").containsOnlyOnce("admin-well-known-status")
                    .containsOnlyOnce("admin-well-known-edit").containsOnlyOnce("admin-well-known-delete");
            assertThat(rows[i].split("admin-member-id", -1)).hasSize(4);
        }
        assertThat(rows[1].indexOf("admin-well-known-status")).isLessThan(rows[1].indexOf("admin-member-id"));
        assertThat(rows[2].indexOf("admin-well-known-status")).isLessThan(rows[2].indexOf("admin-member-id"));
    }

    @Test
    void wellKnownFile_unusualStatus_selectsOtherAndShowsTheCode() throws Exception {
        when(apiService.adminWellKnownFile(7)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto(
                7, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT, "teapot", 418, java.util.List.of(), "text/plain", "", null));

        String html = asAdmin("/spa/admin/meta/file?id=7");
        String select = html.substring(html.indexOf("<mdui-select name=\"status\""));
        select = select.substring(0, select.indexOf(">"));
        String other = html.substring(html.indexOf("<mdui-text-field name=\"statusOther\""));
        other = other.substring(0, other.indexOf(">"));

        assertThat(select).contains("value=\"other\"");
        assertThat(other).contains("value=\"418\"").doesNotContain("hidden");
    }

    @Test
    void wellKnownSchema_isRelayedToAnAdmin_404WhenNone_andRefusedToOthers() throws Exception {
        mvc.perform(get("/spa/admin/meta/schema?path=gpc.json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/spa/admin/meta/schema?path=gpc.json").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isForbidden());

        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminWellKnownSchema("gpc.json")).thenReturn("{\"type\":\"object\"}");
        mvc.perform(get("/spa/admin/meta/schema?path=gpc.json").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("{\"type\":\"object\"}"));
        mvc.perform(get("/spa/admin/meta/schema?path=none").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());
    }

    private static fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto notification(
            String audience, String login, String body) {
        return new fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000042"), "Maintenance <b>", body, "<p>x</p>",
                fr.enimaloc.catapult.common.dto.notification.Severity.WARNING, null, null, null,
                java.time.Instant.parse("2025-03-01T12:00:00Z"), audience, null, login, "TARGETED".equals(audience) && login != null ? 1 : 0);
    }

    @Test
    void notifications_rendersTheListAndTheDialog_withoutLoadingTheMembers() throws Exception {
        when(apiService.adminNotifications(2)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.AdminPage<>(
                List.of(notification("BROADCAST", null, "y".repeat(200)), notification("TARGETED", "alice", "short")), 2, 3, 41));

        String html = asAdmin("/spa/admin/notifications?page=2");

        assertThat(html).contains("admin-notifications", "id=\"admin-notifications-new\"", "data-delete=\"00000000-0000-0000-0000-000000000042\"",
                        "Maintenance &lt;b&gt;", "Everyone", "alice", "id=\"admin-notifications-dialog\"", "maxlength=\"255\"",
                        "maxlength=\"64\"", "maxlength=\"512\"", "admin-notifications-members",
                        "data-admin-results", "data-admin-page=\"1\"", "data-label-cta-pair=\"", "data-label-invalid=\"",
                        "data-label-unavailable=\"", "notification-preview")
                .contains("y".repeat(120)).doesNotContain("y".repeat(121)).doesNotContain("admin-notifications-member-option");
        verify(apiService, never()).adminMembers(anyString(), anyInt());
        verify(apiService, never()).adminSearchMembers(anyString());
    }

    @Test
    void notifications_offersTheExperimentsAndTheirVariantsAsAnAudience() throws Exception {
        when(apiService.adminNotifications(0)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.AdminPage<>(List.of(), 0, 0, 0));
        when(apiService.adminExperiments()).thenReturn(List.of(
                new ExperimentSummaryDto(EXP_ID, "dark-mode", "Dark mode", ExperimentStatus.ACTIVE, variants(), 0),
                new ExperimentSummaryDto(UUID.randomUUID(), "old-one", "Old one", ExperimentStatus.ENDED, variants(), 0)));

        String html = asAdmin("/spa/admin/notifications");

        assertThat(html).contains("value=\"EXPERIMENT\"", "Experiment participants", "admin-notifications-experiment",
                        "value=\"" + EXP_ID + "\"", "Dark mode", "Old one (Ended)", "admin-notifications-variant", "All variants",
                        "admin-notifications-variants-of", "data-variant-id=\"" + ENABLED_ID + "\"", "Enabled", "admin-notifications-count", "data-label-sent=\"")
                .contains("data-label-recipients=\"");
    }

    @Test
    void notifications_stillRenderWhenTheExperimentsAreUnavailable() throws Exception {
        when(apiService.adminNotifications(0)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.AdminPage<>(List.of(), 0, 0, 0));
        when(apiService.adminExperiments()).thenReturn(null);

        assertThat(asAdmin("/spa/admin/notifications")).contains("id=\"admin-notifications-new\"", "admin-notifications-experiment");
    }

    @Test
    void notifications_showHowManyMembersASharedNotificationReached() throws Exception {
        var shared = new fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto(
                java.util.UUID.randomUUID(), "Shared", "b", "<p>b</p>",
                fr.enimaloc.catapult.common.dto.notification.Severity.INFO, null, null, null,
                java.time.Instant.parse("2025-03-01T12:00:00Z"), "TARGETED", null, null, 1200);
        when(apiService.adminNotifications(0)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.AdminPage<>(
                List.of(shared, notification("TARGETED", "alice", "short")), 0, 1, 2));

        assertThat(asAdmin("/spa/admin/notifications")).contains("1,200 members", "alice");
    }

    @Test
    void notifications_saysSoWhenTheApiIsUnavailable() throws Exception {
        when(apiService.adminNotifications(0)).thenReturn(null);

        assertThat(asAdmin("/spa/admin/notifications")).contains("admin-placeholder", "id=\"admin-notifications-new\"")
                .doesNotContain("data-delete");
    }

    @Test
    void notifications_saysWhenThereAreNone() throws Exception {
        when(apiService.adminNotifications(0)).thenReturn(new fr.enimaloc.catapult.common.dto.admin.AdminPage<>(List.of(), 0, 0, 0));
        assertThat(asAdmin("/spa/admin/notifications")).contains("No notification yet");
    }

    private static fr.enimaloc.catapult.common.dto.admin.DtddProposalDto proposal(String status, String login, String reason) {
        return new fr.enimaloc.catapult.common.dto.admin.DtddProposalDto(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000042"),
                java.time.Instant.parse("2025-03-01T12:00:00Z"), "100", 4521L,
                login == null ? null : new fr.enimaloc.catapult.common.dto.admin.DtddProposalDto.Proposer(login), reason, status);
    }

    @Test
    void proposals_listsTheFilter_andOnlyPendingRowsHaveActions() throws Exception {
        when(apiService.adminDtddProposals("PENDING")).thenReturn(List.of(proposal("PENDING", "alice", "wrong <game>"), proposal("PENDING", null, null)));
        when(apiService.adminDtddProposals("APPROVED")).thenReturn(List.of(proposal("APPROVED", "bob", "ok")));

        String pending = asAdmin("/spa/admin/dtdd-mapping");

        assertThat(pending).contains("admin-dtdd-mapping", "data-status=\"PENDING\"", "data-status-filter=\"PENDING\"",
                        "data-status-filter=\"APPROVED\"", "data-status-filter=\"REJECTED\"", "alice", "100", "4521",
                        "wrong &lt;game&gt;", "03/01/2025", "data-approve=\"00000000-0000-0000-0000-000000000042\"",
                        "data-reject=\"00000000-0000-0000-0000-000000000042\"", "admin-dtdd-dialog", "data-label-conflict=\"")
                .contains(">?<");
        assertThat(pending.split("data-status-filter=", -1)).hasSize(4);

        String approved = asAdmin("/spa/admin/dtdd-mapping?status=APPROVED");

        assertThat(approved).contains("data-status=\"APPROVED\"", "bob").doesNotContain("data-approve=").doesNotContain("data-reject=");
    }

    @Test
    void gameMappings_rendersBothLists_fromTheirOwnPages() throws Exception {
        var mapping = new fr.enimaloc.catapult.common.dto.admin.GameMappingDto(UUID.fromString("00000000-0000-0000-0000-000000000042"),
                "STEAM", "730", "Counter <Strike>", "32399", "Counter-Strike", null, null, 3,
                Instant.parse("2026-10-24T10:00:00Z"), "alice");
        var game = new fr.enimaloc.catapult.common.dto.admin.DetectedGameDto("XBOX", "1942", "Halo Infinite", 7);
        when(apiService.adminGameMappings(1)).thenReturn(new AdminPage<>(List.of(mapping), 1, 3, 51));
        when(apiService.adminMissingGameMappings(2)).thenReturn(new AdminPage<>(List.of(game), 2, 3, 51));

        String html = asAdmin("/spa/admin/game-mappings?page=1&missingPage=2");

        assertThat(html).contains("admin-game-mappings", "Counter &lt;Strike&gt;", "Counter-Strike", "Halo Infinite");
        verify(apiService).adminGameMappings(1);
        verify(apiService).adminMissingGameMappings(2);
    }

    @Test
    void gameMappings_negativePagesAreTheFirst() throws Exception {
        when(apiService.adminGameMappings(0)).thenReturn(new AdminPage<>(List.of(), 0, 0, 0));
        when(apiService.adminMissingGameMappings(0)).thenReturn(new AdminPage<>(List.of(), 0, 0, 0));

        asAdmin("/spa/admin/game-mappings?page=-3&missingPage=-1");

        verify(apiService).adminGameMappings(0);
        verify(apiService).adminMissingGameMappings(0);
    }

    @Test
    void proposals_anyOtherStatusIsPending() throws Exception {
        when(apiService.adminDtddProposals("PENDING")).thenReturn(List.of());

        assertThat(asAdmin("/spa/admin/dtdd-mapping?status=bogus")).contains("data-status=\"PENDING\"");
        verify(apiService, never()).adminDtddProposals("bogus");
    }

    @Test
    void proposals_saySoWhenNoneOrUnavailable() throws Exception {
        when(apiService.adminDtddProposals("PENDING")).thenReturn(List.of());
        assertThat(asAdmin("/spa/admin/dtdd-mapping")).contains("No proposal").doesNotContain("data-approve=");

        when(apiService.adminDtddProposals("REJECTED")).thenReturn(null);
        assertThat(asAdmin("/spa/admin/dtdd-mapping?status=REJECTED")).contains("admin-placeholder", "data-status-filter=\"PENDING\"");
    }

    private static fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto group(String key, String description, int members) {
        return new fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000042"), key, "Name <" + key + ">", description, members);
    }

    @Test
    void groupsFragment_rendersRows_andEmptyState() throws Exception {
        when(apiService.adminGroups()).thenReturn(List.of(group("beta", "For <testers>", 3), group("staff", null, 0)));

        String html = asAdmin("/spa/admin/groups");

        assertThat(html).contains("admin-groups", "beta", "Name &lt;beta&gt;", "For &lt;testers&gt;", ">3<", "staff", ">—<",
                "data-rename=\"00000000-0000-0000-0000-000000000042\"", "data-add-member=\"00000000-0000-0000-0000-000000000042\"",
                "data-delete=\"00000000-0000-0000-0000-000000000042\"", "admin-groups-rename-dialog",
                "admin-groups-member-dialog", "data-label-conflict=\"");

        when(apiService.adminGroups()).thenReturn(List.of());
        assertThat(asAdmin("/spa/admin/groups")).contains("No group").doesNotContain("data-rename=");

        when(apiService.adminGroups()).thenReturn(null);
        assertThat(asAdmin("/spa/admin/groups")).contains("admin-placeholder").doesNotContain("data-rename=");
    }

    private static final UUID INVITE = UUID.fromString("00000000-0000-0000-0000-000000000077");

    private static fr.enimaloc.catapult.common.dto.admin.WhitelistInviteRow invite(Integer maxUses, Boolean canReinvite) {
        return new fr.enimaloc.catapult.common.dto.admin.WhitelistInviteRow(INVITE, UUID.randomUUID(), "own<er>", "ABC234",
                maxUses, 2, canReinvite, java.time.Instant.parse("2026-10-01T10:00:00Z"), List.of());
    }

    private static fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto whitelist(boolean whitelistOn, boolean invitesOn,
            List<fr.enimaloc.catapult.common.dto.admin.WhitelistInviteRow> invites) {
        return new fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto(
                List.of(new fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto.Entry("123"),
                        new fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto.Entry("456")),
                java.util.Map.of("123", "ali<ce>"), whitelistOn, invitesOn, 50, 5, true, true, invites);
    }

    @Test
    void invitationsCard_onlyWhenTheWhitelistIsOn_settingsOnlyWhenInvitesAreOn() throws Exception {
        when(apiService.adminWhitelist()).thenReturn(whitelist(false, true, List.of(invite(3, true))));
        assertThat(asAdmin("/spa/admin/whitelist")).contains("data-whitelist-toggle", "admin-whitelist-add-save",
                "data-delete=\"123\"", "ali&lt;ce&gt;", ">—<")
                .doesNotContain("data-invites-toggle", "admin-whitelist-settings-save", "data-quota=");

        when(apiService.adminWhitelist()).thenReturn(whitelist(true, false, List.of(invite(3, true))));
        assertThat(asAdmin("/spa/admin/whitelist")).contains("data-invites-toggle")
                .doesNotContain("admin-whitelist-settings-save", "data-quota=");

        when(apiService.adminWhitelist()).thenReturn(whitelist(true, true, List.of(invite(3, true))));
        assertThat(asAdmin("/spa/admin/whitelist")).contains("data-invites-toggle", "admin-whitelist-settings-save",
                "name=\"globalMaxMembers\"", "admin-whitelist-cap", "own&lt;er&gt;", "ABC234",
                "data-quota=\"" + INVITE + "\"", "admin-whitelist-quota-dialog");

        when(apiService.adminWhitelist()).thenReturn(null);
        assertThat(asAdmin("/spa/admin/whitelist")).contains("admin-placeholder").doesNotContain("data-whitelist-toggle");
    }

    @Test
    void invites_showDefaultWhenMaxIsNull_andFlagZero() throws Exception {
        when(apiService.adminWhitelist()).thenReturn(whitelist(true, true, List.of(invite(null, null), invite(0, false))));

        String html = asAdmin("/spa/admin/whitelist");

        assertThat(html).contains("(default)", "admin-whitelist-zero", "✗", ">—<");
        // a default (null) is left out, which the dialog reads as empty
        assertThat(html).contains("data-can-reinvite=\"false\"", "data-max-uses=\"0\"");
        assertThat(html.indexOf("data-can-reinvite")).isEqualTo(html.lastIndexOf("data-can-reinvite"));
    }

    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void targeting_rendersFlags_groups_andTheGroupSelect() throws Exception {
        when(apiService.adminMember(MEMBER)).thenReturn(new AdminMemberSummaryDto(MEMBER, "ali<ce>"));
        when(apiService.adminMemberTargeting(MEMBER)).thenReturn(new MemberTargetingDto(
                List.of(new MemberTargetingDto.Flag("a/b %é", "<v>"),
                        new MemberTargetingDto.Flag("beta", null)),
                List.of("staff", "ghost")));
        when(apiService.adminGroups()).thenReturn(List.of(group("staff", null, 1), group("beta", null, 0)));

        String html = asAdmin("/spa/admin/members/" + MEMBER);

        assertThat(html).contains("ali&lt;ce&gt;", "data-admin-back", "admin-targeting",
                "data-flag=\"a/b %é\"", "data-flag-delete=\"a/b %é\"", "&lt;v&gt;", "data-flag=\"beta\"",
                "data-group-id=\"00000000-0000-0000-0000-000000000042\"", "ghost", "admin-targeting-flag-save", "admin-targeting-group-save")
                .contains("value=\"beta\"")  // beta is offered; staff, already joined, is not
                .doesNotContain("value=\"staff\"");
        assertThat(html.indexOf("data-group-id=")).isEqualTo(html.lastIndexOf("data-group-id="));  // ghost has no remove button
        assertThat(html).contains("data-member-id=\"" + MEMBER + "\"");
    }

    @Test
    void targeting_withoutGroupsOrFlags_rendersThemEmpty() throws Exception {
        when(apiService.adminMember(MEMBER)).thenReturn(new AdminMemberSummaryDto(MEMBER, "alice"));
        when(apiService.adminGroups()).thenReturn(List.of());
        when(apiService.adminMemberTargeting(MEMBER)).thenReturn(new MemberTargetingDto(null, null));

        assertThat(asAdmin("/spa/admin/members/" + MEMBER)).contains("admin-targeting-flag-save");
    }

    @Test
    void targeting_whenTheTargetingCantBeLoaded_saysSo() throws Exception {
        when(apiService.adminMember(MEMBER)).thenReturn(new AdminMemberSummaryDto(MEMBER, "alice"));

        assertThat(asAdmin("/spa/admin/members/" + MEMBER)).contains("admin-placeholder").doesNotContain("admin-targeting-flag-save");
    }

    @Test
    void targeting_unknownMember_isTheMissingFragment() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        String html = mvc.perform(get("/spa/admin/members/" + MEMBER).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("admin-placeholder").doesNotContain("admin-targeting-flag-save");
    }

    @Test
    void membersFragment_isThePanelContent_withTheTargetingItem() throws Exception {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(apiService.currentUserId()).thenReturn(MEMBER);
        when(apiService.adminMembers("", 0)).thenReturn(new AdminMembersPage(List.of(
                new AdminMemberDto(other, "2", "viewer", "img", null, false, true, "ACTIVE",
                        Instant.parse("2025-03-01T12:00:00Z")),
                new AdminMemberDto(MEMBER, "1", "catapult_bot", "img", null, true, true, "ACTIVE",
                        Instant.parse("2025-03-01T12:00:00Z"))), Map.of(), false, 0, 1, 1));

        String html = asAdmin("/spa/admin/members");

        assertThat(html).doesNotContain("<mdui-tab-panel").contains("id=\"admin-members\"", "data-admin-open=\"" + other + "\"")
                .doesNotContain("data-admin-open=\"" + MEMBER + "\"");
    }

    @Test
    void membersFragment_passesThePageAndTheSearchThrough_andRendersThePaginationAndTheSearchField() throws Exception {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(apiService.adminMembers("vie", 1)).thenReturn(new AdminMembersPage(List.of(
                new AdminMemberDto(other, "2", "viewer", "img", null, false, true, "ACTIVE",
                        Instant.parse("2025-03-01T12:00:00Z"))), Map.of(), false, 1, 3, 101));

        String html = asAdmin("/spa/admin/members?q=vie&page=1");

        verify(apiService).adminMembers("vie", 1);
        assertThat(html).contains("data-admin-results", "data-admin-search", "value=\"vie\"", "data-admin-page=\"0\"",
                "data-admin-page=\"2\"", "2 / 3", "101", "data-admin-open=\"" + other + "\"");
    }

    @Test
    void membersFragment_onASingleOrEmptyPage_hasNoPagination_andSaysNoMatchForASearch() throws Exception {
        when(apiService.adminMembers("zzz", 0)).thenReturn(new AdminMembersPage(List.of(), Map.of(), false, 0, 0, 0));

        String html = asAdmin("/spa/admin/members?q=zzz");

        assertThat(html).contains("data-admin-results", "Nothing matches this search").doesNotContain("data-admin-page=");
    }

    @Test
    void membersFragment_withoutParameters_isTheFirstPageOfEveryone() throws Exception {
        when(apiService.adminMembers("", 0)).thenReturn(new AdminMembersPage(List.of(), Map.of(), false, 0, 0, 0));

        asAdmin("/spa/admin/members");

        verify(apiService).adminMembers("", 0);
    }

    @Test
    void searchMembers_answersTheLoginsToPickFrom_skippingTheNamelessOnesAndTheSystemAccount() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant at = Instant.parse("2025-03-01T12:00:00Z");
        when(apiService.adminSearchMembers("vie")).thenReturn(List.of(
                new AdminMemberDto(id, "2", "viewer", "img", null, false, true, "ACTIVE", at),
                new AdminMemberDto(UUID.randomUUID(), null, null, null, null, false, false, "INACTIVE", at),
                new AdminMemberDto(UUID.randomUUID(), "99", "catapult_bot", "img", null, true, true, "ACTIVE", at)));

        String json = asAdmin("/spa/admin/members/search?q=vie");

        assertThat(json).isEqualTo("[{\"id\":\"" + id + "\",\"login\":\"viewer\"}]");
    }

    @Test
    void searchMembers_is503WhenTheApiFails() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminSearchMembers("vie")).thenReturn(null);

        mvc.perform(get("/spa/admin/members/search?q=vie").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isServiceUnavailable());
    }

    /** Enabled only for another active member with a Twitch account; the system account has none. */
    @Test
    void impersonateItem_isDisabledForSelfInactiveAndTwitchless_andHiddenForTheSystemAccount() throws Exception {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID inactive = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID bot = UUID.fromString("00000000-0000-0000-0000-000000000004");
        Instant at = Instant.parse("2025-03-01T12:00:00Z");
        when(apiService.currentUserId()).thenReturn(MEMBER);
        when(apiService.adminMembers("", 0)).thenReturn(new AdminMembersPage(List.of(
                new AdminMemberDto(MEMBER, "1", "me", "img", null, false, true, "ACTIVE", at),
                new AdminMemberDto(other, "2", "viewer", "img", null, false, true, "ACTIVE", at),
                new AdminMemberDto(inactive, "3", "gone", "img", null, false, false, "INACTIVE", at),
                new AdminMemberDto(bot, "4", "catapult_bot", "img", null, true, true, "ACTIVE", at)), Map.of(), false, 0, 1, 1));

        String html = asAdmin("/spa/admin/members");

        assertThat(html).containsPattern("icon=\"supervisor_account\"\\s+data-impersonate=\"viewer\">")
                .containsPattern("icon=\"supervisor_account\"\\s+data-impersonate=\"me\" disabled=\"disabled\">")
                .containsPattern("icon=\"supervisor_account\"\\s+data-impersonate=\"gone\" disabled=\"disabled\">")
                .doesNotContain("data-impersonate=\"catapult_bot\"")
                .contains("data-label-impersonate-refused=");
    }

    @Test
    void systemAccount_getsOnlyEditSettings() throws Exception {
        when(apiService.currentUserId()).thenReturn(MEMBER);
        when(apiService.adminMembers("", 0)).thenReturn(new AdminMembersPage(List.of(
                new AdminMemberDto(MEMBER, "1", "catapult_bot", "img", null, true, true, "ACTIVE",
                        Instant.parse("2025-03-01T12:00:00Z"))), Map.of(), false, 0, 1, 1));

        String html = asAdmin("/spa/admin/members");

        assertThat(html).contains("href=\"/channel/catapult_bot\"")
                .doesNotContain("data-action=", "data-admin-open=");
    }

    @Test
    void broadcast_showsTheRememberedMaintenance_withTheTypeForms() throws Exception {
        when(broadcastState.maintenance()).thenReturn(java.util.Optional.of(new LinkedHashMap<>(Map.of(
                "startsAt", "2026-10-20T20:00:00Z", "durationMinutes", 30, "message", "Planned <b>work</b>"))));

        String html = asAdmin("/spa/admin/broadcast");

        assertThat(html).contains("admin-broadcast", "data-current-starts-at=\"2026-10-20T20:00:00Z\"", "Planned &lt;b&gt;work&lt;/b&gt;",
                "id=\"admin-broadcast-cancel-current\"", "data-broadcast=\"maintenance.scheduled\"", "data-broadcast=\"maintenance.cancelled\"",
                "data-broadcast=\"version.deployed\"", "data-broadcast=\"alert.info\"", "data-broadcast=\"alert.warning\"",
                "maxlength=\"500\"", "maxlength=\"64\"", "maxlength=\"120\"", "maxlength=\"2000\"",
                "data-label-sent=\"", "data-label-invalid=\"", "data-label-rate=\"", "data-label-unavailable=\"");
    }

    @Test
    void broadcast_hidesTheCurrentCard_whenNoMaintenanceIsAnnounced() throws Exception {
        when(broadcastState.maintenance()).thenReturn(java.util.Optional.empty());

        assertThat(asAdmin("/spa/admin/broadcast")).contains("data-broadcast=\"alert.info\"")
                .doesNotContain("admin-broadcast-cancel-current");
    }

    private static fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto keys(boolean enabled,
            fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto.Key... keys) {
        return new fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto(enabled, List.of(keys));
    }

    @Test
    void disabledProvider_showsOnlyTheNote() throws Exception {
        when(apiService.adminApiKeys(fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.STEAM)).thenReturn(keys(false));
        when(apiService.adminApiKeys(fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.DTDD)).thenReturn(keys(true));

        String html = asAdmin("/spa/admin/keys");

        assertThat(html).contains("data-provider=\"steam\"", "data-provider=\"dtdd\"", "id=\"admin-minecraft\"", "steam.enabled=true");
        // Steam: no add field, no refresh; DTDD (enabled): one of each
        assertThat(html.split("admin-keys-add-save", -1)).hasSize(2);
        assertThat(html.split("admin-keys-refresh", -1)).hasSize(2);
    }

    @Test
    void keys_areShownMasked_neverRaw() throws Exception {
        var key = new fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto.Key("id-1", "RAWK…Y123", "own<er>", true, 42);
        var free = new fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto.Key("id-2", "abcd…wxyz", null, false, 0);
        when(apiService.adminApiKeys(fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.STEAM)).thenReturn(keys(true, key, free));
        when(apiService.adminApiKeys(fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.DTDD)).thenReturn(keys(true));

        String html = asAdmin("/spa/admin/keys");

        assertThat(html).contains("RAWK…Y123", "abcd…wxyz", "own&lt;er&gt;", "data-key-id=\"id-1\"", "Blocked — 42s")
                .doesNotContain("RAWKEY123");
    }

    @Test
    void unreachableApi_showsThePlaceholder() throws Exception {
        when(apiService.adminApiKeys(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        assertThat(asAdmin("/spa/admin/keys")).contains("admin-placeholder").doesNotContain("admin-keys-add-save");
    }

    private static fr.enimaloc.catapult.common.dto.admin.AccountDto account(String id, String label, long links,
            boolean enabled, boolean full) {
        return new fr.enimaloc.catapult.common.dto.admin.AccountDto(java.util.UUID.fromString(id), label,
                label + "_mc", 0, full, enabled, (int) links);
    }

    @Test
    void accountsCard_statesAndActions() throws Exception {
        when(apiService.adminApiKeys(org.mockito.ArgumentMatchers.any())).thenReturn(keys(true));
        when(apiService.adminMinecraftAccounts()).thenReturn(List.of(
                account("00000000-0000-0000-0000-00000000000a", "Act<ive>", 3, true, false),
                account("00000000-0000-0000-0000-00000000000b", "Off", 0, false, false),
                account("00000000-0000-0000-0000-00000000000c", "Full", 1, true, true)));

        String html = asAdmin("/spa/admin/keys").replaceAll("\\s+", " ");

        assertThat(html).contains("id=\"admin-minecraft\"", "Act&lt;ive&gt;", "Off_mc", "admin-minecraft-add",
                "Minecraft service accounts");
        // the state chips themselves: enabled ×2, disabled ×1, full ×1 (not the seeded "Full" label)
        assertThat(html.split("<mdui-chip icon=\"check_circle\">Enabled</mdui-chip>", -1)).hasSize(3);
        assertThat(html.split("<mdui-chip icon=\"block\">Disabled</mdui-chip>", -1)).hasSize(2);
        assertThat(html.split("<mdui-chip icon=\"group_off\" class=\"admin-minecraft-full\">Full</mdui-chip>", -1)).hasSize(2);
        // disabled → re-authenticate (only that one); full → reset limit (only that one); each: toggle and delete
        assertThat(html.split("admin-minecraft-reauth\"", -1)).hasSize(2);
        assertThat(html).contains("admin-minecraft-reauth\" data-id=\"00000000-0000-0000-0000-00000000000b\"");
        assertThat(html.split("admin-minecraft-reset\"", -1)).hasSize(2);
        assertThat(html).contains("admin-minecraft-reset\" data-id=\"00000000-0000-0000-0000-00000000000c\"");
        assertThat(html.split("admin-minecraft-toggle\"", -1)).hasSize(4);
        assertThat(html).contains("data-enabled=\"false\"", "data-enabled=\"true\"");
        assertThat(html.split("admin-minecraft-delete\"", -1)).hasSize(4);
    }

    @Test
    void accountsCard_emptyAndUnavailable() throws Exception {
        when(apiService.adminApiKeys(org.mockito.ArgumentMatchers.any())).thenReturn(keys(true));
        when(apiService.adminMinecraftAccounts()).thenReturn(List.of());
        assertThat(asAdmin("/spa/admin/keys")).contains("No service account yet.", "admin-minecraft-add");

        when(apiService.adminMinecraftAccounts()).thenReturn(null);
        assertThat(asAdmin("/spa/admin/keys")).contains("admin-minecraft-unavailable").doesNotContain("admin-minecraft-add");
    }

    @Test
    void twFragment_listsDefinitions_andManageKeywordsLinks() throws Exception {
        when(apiService.adminTws()).thenReturn(List.of(
                new fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto("gore", "Go<re>", null, true, 10),
                new fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto("spiders", "Spiders", null, false, 20)));

        String html = asAdmin("/spa/admin/tw");

        assertThat(html).contains("data-id=\"gore\"", "Go&lt;re&gt;", "data-id=\"spiders\"",
                "data-admin-open=\"gore\"", "data-admin-open=\"spiders\"", "admin-tw-add-save",
                "data-rebuild=\"untouched\"", "data-rebuild=\"non-overridden\"", "data-rebuild=\"force-pinned\"",
                "This will OVERWRITE the TW choices");
        assertThat(html.split("admin-tw-danger", -1)).hasSize(2);
    }

    @Test
    void twFragment_unreachableApi_showsThePlaceholder() throws Exception {
        when(apiService.adminTws()).thenReturn(null);

        assertThat(asAdmin("/spa/admin/tw")).contains("admin-placeholder").doesNotContain("data-admin-open");
    }

    @Test
    void twFragment_noDefinition_saysSo() throws Exception {
        when(apiService.adminTws()).thenReturn(List.of());

        assertThat(asAdmin("/spa/admin/tw")).contains("No trigger warning defined.").doesNotContain("data-admin-open");
    }

    @Test
    void twKeywordsFragment_titleBackLinkListAndTester() throws Exception {
        when(apiService.adminTw("gore")).thenReturn(new TwDefinitionDto("gore", "Go<re>", null, true, 1));
        when(apiService.adminTwKeywords("gore")).thenReturn(List.of("blood", "a/b <i>"));

        String html = asAdmin("/spa/admin/tw/gore");

        assertThat(html).contains("data-admin-back", "Go&lt;re&gt;", "data-tw-id=\"gore\"",
                "data-keyword=\"blood\"", "data-keyword=\"a/b &lt;i&gt;\"", "admin-tw-keyword-add", "admin-tw-keyword-remove",
                "admin-tw-test", "name=\"appId\"", "admin-tw-test-result")
                .doesNotContain("<i>");
    }

    @Test
    void twKeywordsFragment_noKeywords_saysSo_andUnreadableKeywordsShowThePlaceholder() throws Exception {
        when(apiService.adminTw("gore")).thenReturn(new TwDefinitionDto("gore", "Gore", null, true, 1));
        when(apiService.adminTwKeywords("gore")).thenReturn(List.of());
        assertThat(asAdmin("/spa/admin/tw/gore")).contains("admin-tw-keyword-add").doesNotContain("admin-tw-keyword-remove");

        when(apiService.adminTwKeywords("gore")).thenReturn(null);
        assertThat(asAdmin("/spa/admin/tw/gore")).contains("admin-placeholder").doesNotContain("admin-tw-keyword-add");
    }

    @Test
    void twKeywordsFragment_unknownDefinition_isTheMissingFragment() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        String html = mvc.perform(get("/spa/admin/tw/nope").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("admin-placeholder", "data-admin-back").doesNotContain("admin-tw-keyword-add");
    }

    private static final UUID EXP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ENABLED_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static List<ExperimentVariantDto> variants() {
        return List.of(new ExperimentVariantDto(CONTROL_ID, "control", "Control", 0, true),
                new ExperimentVariantDto(ENABLED_ID, "enabled", "Enabled", 100, false));
    }

    private ExperimentSettingsDto experiment(ExperimentStatus status, List<ExperimentRuleDto> rules,
                                             List<ExperimentOverrideDto> overrides) {
        var settings = new ExperimentSettingsDto(EXP_ID, "dark-mode", "Dark mode", "Try the dark theme", status,
                variants(), rules, overrides);
        when(apiService.adminExperiment(EXP_ID)).thenReturn(settings);
        when(apiService.adminGroups()).thenReturn(List.of(new GroupSummaryDto(UUID.randomUUID(), "beta", "Beta", null, 2)));
        when(apiService.adminFlagKeys()).thenReturn(List.of(new FlagKeyDto("beta", List.of("on"))));
        return settings;
    }

    @Test
    void experiments_listsEachOneWithItsWeights_andWarnsWhenNoRule() throws Exception {
        when(apiService.adminExperiments()).thenReturn(List.of(
                new ExperimentSummaryDto(EXP_ID, "dark-mode", "Dark mode", ExperimentStatus.ACTIVE, variants(), 0),
                new ExperimentSummaryDto(UUID.randomUUID(), "invite", "Invite", ExperimentStatus.DRAFT, variants(), 2)));

        String html = asAdmin("/spa/admin/experiments");

        assertThat(html).contains("data-admin-open=\"" + EXP_ID + "\"", "dark-mode", "invite", "control 0 %", "enabled 100 %");
        assertThat(html.split("admin-experiments-norule", -1)).hasSize(2);
    }

    @Test
    void experiments_showTheWeightsAsSharesOfTheirTotal() throws Exception {
        var shares = List.of(new ExperimentVariantDto(CONTROL_ID, "control", "Control", 1, true),
                new ExperimentVariantDto(ENABLED_ID, "enabled", "Enabled", 3, false));
        when(apiService.adminExperiments()).thenReturn(List.of(
                new ExperimentSummaryDto(EXP_ID, "dark-mode", "Dark mode", ExperimentStatus.ACTIVE, shares, 1),
                new ExperimentSummaryDto(UUID.randomUUID(), "invite", "Invite", ExperimentStatus.DRAFT,
                        List.of(new ExperimentVariantDto(UUID.randomUUID(), "off", "Off", 0, true)), 1)));

        assertThat(asAdmin("/spa/admin/experiments")).contains("control 25 %", "enabled 75 %", "off 0 %");
    }

    @Test
    void experimentDetail_ofAnEndedOne_showsTheWeightsAsShares() throws Exception {
        var settings = new ExperimentSettingsDto(EXP_ID, "dark-mode", "Dark mode", null, ExperimentStatus.ENDED,
                List.of(new ExperimentVariantDto(CONTROL_ID, "control", "Control", 1, true),
                        new ExperimentVariantDto(ENABLED_ID, "enabled", "Enabled", 3, false)), List.of(), List.of());
        when(apiService.adminExperiment(EXP_ID)).thenReturn(settings);
        when(apiService.adminGroups()).thenReturn(List.of());
        when(apiService.adminFlagKeys()).thenReturn(List.of());

        assertThat(asAdmin("/spa/admin/experiments/" + EXP_ID)).contains(">25 %<", ">75 %<");
    }

    @Test
    void experimentDetail_drawsTheWeightsAsABar_controlInGrey_withAHandleBetweenNeighbours() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID);

        assertThat(html).contains("experiment-weight-bar is-editable",
                "data-segment=\"" + CONTROL_ID + "\"", "--variant-color: var(--catapult-variant-control)",
                "data-segment=\"" + ENABLED_ID + "\"", "--variant-color: var(--catapult-variant-1)",
                "role=\"slider\"", "data-handle=\"1\"", "aria-label=\"Between control and enabled\"");
        assertThat(html.split("data-handle=", -1)).hasSize(2);
    }

    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private ExperimentAssignmentsDto participants(int page, int totalPages, ExperimentAssignmentDto... rows) {
        return new ExperimentAssignmentsDto(new AdminPage<>(List.of(rows), page, totalPages, rows.length),
                Map.of(CONTROL_ID, 3L, ENABLED_ID, 5L));
    }

    private static ExperimentAssignmentDto assignment(UUID user, String login, UUID variant, boolean forced) {
        return new ExperimentAssignmentDto(user, login, variant, Instant.parse("2026-03-04T10:15:30Z"), forced);
    }

    @Test
    void experimentDetail_listsTheParticipants_withCounts_aForcedChip_andAMoveToTheOtherVariants() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());
        when(apiService.adminExperimentAssignments(EXP_ID, null, null, 0)).thenReturn(participants(0, 1,
                assignment(USER_A, "alice", CONTROL_ID, false), assignment(USER_B, "bob", ENABLED_ID, true)));

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID);

        assertThat(html).contains("id=\"experiment-participants\"", "data-participants", "data-page=\"0\"",
                "All (8)", "data-participants-variant=\"" + CONTROL_ID + "\"", "control (3)",
                "data-participants-variant=\"" + ENABLED_ID + "\"", "enabled (5)",
                "--variant-color: var(--catapult-variant-control)", "--variant-color: var(--catapult-variant-1)",
                "data-participants-search", "alice", "bob", "03/04/2026", "id=\"experiment-overrides\"");
        assertThat(html.split("admin-participants-forced", -1)).hasSize(2);
        // each row offers the other variants only
        String alice = html.substring(html.indexOf("data-participants-move=\"" + USER_A + "\""));
        alice = alice.substring(0, alice.indexOf("</mdui-select>"));
        assertThat(alice).contains("value=\"" + ENABLED_ID + "\"").doesNotContain("value=\"" + CONTROL_ID + "\"");
        String bob = html.substring(html.indexOf("data-participants-move=\"" + USER_B + "\""));
        bob = bob.substring(0, bob.indexOf("</mdui-select>"));
        assertThat(bob).contains("value=\"" + CONTROL_ID + "\"").doesNotContain("value=\"" + ENABLED_ID + "\"");
        assertThat(html).doesNotContain("data-participants-page");
    }

    @Test
    void experimentDetail_ofAnEndedOne_listsTheParticipantsWithoutAMove() throws Exception {
        var settings = new ExperimentSettingsDto(EXP_ID, "dark-mode", "Dark mode", null, ExperimentStatus.ENDED,
                variants(), List.of(), List.of());
        when(apiService.adminExperiment(EXP_ID)).thenReturn(settings);
        when(apiService.adminExperimentAssignments(EXP_ID, null, null, 0))
                .thenReturn(participants(0, 1, assignment(USER_A, "alice", CONTROL_ID, false)));

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID);

        assertThat(html).contains("alice").doesNotContain("data-participants-move");
    }

    @Test
    void experimentDetail_withNoParticipantAtAll_saysNobodyIsAssignedYet() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());
        when(apiService.adminExperimentAssignments(EXP_ID, null, null, 0)).thenReturn(new ExperimentAssignmentsDto(
                new AdminPage<>(List.of(), 0, 0, 0), Map.of(CONTROL_ID, 0L, ENABLED_ID, 0L)));

        assertThat(asAdmin("/spa/admin/experiments/" + EXP_ID)).contains("Nobody has been assigned yet")
                .doesNotContain("No participant matches");
    }

    @Test
    void participants_passTheVariantSearchAndPageToTheApi_andPageThroughTheResults() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());
        when(apiService.adminExperimentAssignments(EXP_ID, ENABLED_ID, "bo", 1))
                .thenReturn(participants(1, 3, assignment(USER_B, "bob", ENABLED_ID, false)));

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID + "/participants?variant=" + ENABLED_ID + "&q=bo&page=1");

        verify(apiService).adminExperimentAssignments(EXP_ID, ENABLED_ID, "bo", 1);
        assertThat(html).contains("data-variant=\"" + ENABLED_ID + "\"", "data-q=\"bo\"", "data-page=\"1\"", "bob")
                .contains("2 / 3", "data-participants-page=\"0\"", "data-participants-page=\"2\"")
                .doesNotContain("data-admin-page", "id=\"experiment-overrides\"");
    }

    @Test
    void participants_pastTheLastPage_landOnTheLastOneLeft() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());
        when(apiService.adminExperimentAssignments(EXP_ID, ENABLED_ID, null, 2))
                .thenReturn(new ExperimentAssignmentsDto(new AdminPage<>(List.of(), 2, 2, 20), Map.of()));
        when(apiService.adminExperimentAssignments(EXP_ID, ENABLED_ID, null, 1))
                .thenReturn(participants(1, 2, assignment(USER_B, "bob", ENABLED_ID, false)));

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID + "/participants?variant=" + ENABLED_ID + "&page=2");

        assertThat(html).contains("bob", "data-page=\"1\"", "2 / 2").doesNotContain("No participant matches");
    }

    @Test
    void participants_withoutAMatch_saysNoParticipantMatches() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());
        when(apiService.adminExperimentAssignments(EXP_ID, null, "zz", 0)).thenReturn(participants(0, 0));

        assertThat(asAdmin("/spa/admin/experiments/" + EXP_ID + "/participants?q=zz"))
                .contains("No participant matches").doesNotContain("Nobody has been assigned yet");
    }

    @Test
    void participants_ofAnUnknownExperiment_isTheMissingFragment() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(get("/spa/admin/experiments/" + EXP_ID + "/participants").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void experimentDetail_ofAnEndedOne_drawsTheBarWithoutHandles() throws Exception {
        experiment(ExperimentStatus.ENDED, List.of(), List.of());

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID);

        assertThat(html).contains("experiment-weight-bar", "data-segment=\"" + ENABLED_ID + "\"")
                .doesNotContain("is-editable", "data-handle");
    }

    @Test
    void experiments_drawAMiniBarPerExperiment() throws Exception {
        when(apiService.adminExperiments()).thenReturn(List.of(
                new ExperimentSummaryDto(EXP_ID, "dark-mode", "Dark mode", ExperimentStatus.ACTIVE, variants(), 1)));

        assertThat(asAdmin("/spa/admin/experiments")).contains("experiment-weight-bar is-mini", "flex-grow: 100")
                .doesNotContain("data-handle");
    }

    @Test
    void experimentDetail_explainsThePriorityDirection() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());

        assertThat(asAdmin("/spa/admin/experiments/" + EXP_ID)).contains("Lower wins");
    }

    @Test
    void experiments_saysSo_whenCatapultApiFails() throws Exception {
        when(apiService.adminExperiments()).thenReturn(null);

        assertThat(asAdmin("/spa/admin/experiments")).contains("data-admin-retry").doesNotContain("data-admin-open");
    }

    @Test
    void experimentDetail_ofADraft_offersOnlyToActivate() throws Exception {
        experiment(ExperimentStatus.DRAFT, List.of(), List.of());

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID);

        assertThat(html).contains("data-experiment=\"" + EXP_ID + "\"", "data-status=\"DRAFT\"", "data-admin-back",
                "data-experiment-action=\"activate\"",
                "id=\"experiment-weights\"", "data-variant=\"" + ENABLED_ID + "\"", "id=\"experiment-rule-form\"",
                "id=\"experiment-override-form\"", "experiment-rule-warning", "<datalist")
                .doesNotContain("data-experiment-action=\"pause\"", "data-experiment-action=\"end\"",
                        "data-experiment-action=\"reassign\"");
    }

    @Test
    void experimentDetail_ofAPausedOne_offersToReactivateAndEnd() throws Exception {
        experiment(ExperimentStatus.PAUSED, List.of(), List.of());

        assertThat(asAdmin("/spa/admin/experiments/" + EXP_ID)).contains("data-experiment-action=\"activate\"",
                "data-experiment-action=\"end\"").doesNotContain("data-experiment-action=\"pause\"", "data-experiment-action=\"reassign\"");
    }

    @Test
    void experimentDetail_ofAnActiveOne_offersToPauseAndEnd() throws Exception {
        experiment(ExperimentStatus.ACTIVE, List.of(), List.of());

        assertThat(asAdmin("/spa/admin/experiments/" + EXP_ID)).contains("data-experiment-action=\"pause\"",
                "data-experiment-action=\"end\"", "data-experiment-action=\"reassign\"").doesNotContain("data-experiment-action=\"activate\"");
    }

    @Test
    void experimentDetail_ofAnEndedOne_isReadOnly() throws Exception {
        var ruleId = UUID.randomUUID();
        experiment(ExperimentStatus.ENDED, List.of(new ExperimentRuleDto(ruleId, "RANDOM", 100, null, null, null)), List.of());

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID);

        assertThat(html).contains("data-status=\"ENDED\"", "Random 100 %")
                .doesNotContain("data-experiment-action", "experiment-weights", "experiment-rule-form",
                        "experiment-override-form", "data-rule-delete");
    }

    @Test
    void experimentDetail_readsRulesAndOverridesInPlainWords() throws Exception {
        var ruleIds = new UUID[5];
        for (int i = 0; i < 5; i++) ruleIds[i] = UUID.randomUUID();
        var overrideIds = new UUID[5];
        for (int i = 0; i < 5; i++) overrideIds[i] = UUID.randomUUID();
        experiment(ExperimentStatus.ACTIVE, List.of(
                new ExperimentRuleDto(ruleIds[0], "RANDOM", 100, null, null, null),
                new ExperimentRuleDto(ruleIds[1], "GROUP", null, "group", "in", "beta"),
                new ExperimentRuleDto(ruleIds[2], "ATTRIBUTE", null, "flag:beta", "eq", "on"),
                new ExperimentRuleDto(ruleIds[3], "EXPERIMENT", null, "other", "eq", "enabled"),
                new ExperimentRuleDto(ruleIds[4], "MANUAL", null, null, null, null)), List.of(
                new ExperimentOverrideDto(overrideIds[0], "USER", "FORCE_INCLUDE", 10, "enimaloc", null, null, null, null),
                new ExperimentOverrideDto(overrideIds[1], "ATTRIBUTE", "FORCE_EXCLUDE", 20, null, "flag:beta", "exists", null, null),
                new ExperimentOverrideDto(overrideIds[2], "ATTRIBUTE", "FORCE_EXCLUDE", 30, null, "flag:beta", "eq", "on", null),
                new ExperimentOverrideDto(overrideIds[3], "ATTRIBUTE", "FORCE_VARIANT", 40, null, "group", "in", "beta", ENABLED_ID)));

        String html = asAdmin("/spa/admin/experiments/" + EXP_ID);

        assertThat(html).contains("Random 100 %", "Group beta", "Tag beta = on", "Experiment other = enabled", "Manual",
                        "Account enimaloc", "Include", "Exclude", "Force \u00ab enabled \u00bb")
                .contains("data-rule-delete=\"" + ruleIds[0] + "\"", "data-override-delete=\"" + overrideIds[3] + "\"")
                .doesNotContain("experiment-rule-warning");
        assertThat(html).containsSubsequence("Account enimaloc", "Tag beta", "Tag beta = on", "Group beta");
    }

    @Test
    void experimentDetail_is404_whenTheExperimentIsUnknown() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        String html = mvc.perform(get("/spa/admin/experiments/" + EXP_ID).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-admin-back").doesNotContain("data-experiment=");
    }
}
