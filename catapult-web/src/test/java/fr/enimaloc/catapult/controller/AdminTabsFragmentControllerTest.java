package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminCclDto;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
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
                "/spa/admin/caches/igdb-game-cache/entry?key=1")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isForbidden());
        }
        verify(apiService, org.mockito.Mockito.atLeastOnce()).isAdmin();
        verify(apiService, never()).adminCcl();
        verify(apiService, never()).adminCaches();
        verify(apiService, never()).adminCacheEntries(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(apiService, never()).adminCacheEntry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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
                "Graphic violence", "<mdui-menu-item value=\"1\">ESRB — Violence</mdui-menu-item>");
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
}
