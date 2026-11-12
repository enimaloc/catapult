package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DtddCacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.DtddCachesSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddGameCacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddKeyQuotaDto;
import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.common.dto.admin.DtddSearchCacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicsCacheEntryDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin DTDD caches tab as it renders: its summary (refetch, caches, quotas) and each cache's entries. */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminDtddCachesRenderingTest {

    private static final Instant AT = Instant.parse("2026-11-12T10:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean BroadcastState broadcastState;

    @BeforeEach
    void stubTheCaches() {
        when(apiService.isAdmin()).thenReturn(true);
    }

    private static DtddCachesSummaryDto summary(DtddRefetchJobDto job, List<DtddKeyQuotaDto> quotas) {
        return new DtddCachesSummaryDto(4, 1, 3, 0, 2, 2, 168, 180 * 24, 12, quotas, 500, job);
    }

    private String render(String path, Locale locale) throws Exception {
        return mvc.perform(get(path).locale(locale).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void summary_offersTheRefetch_opensEachCache_andTellsTheQuotas() throws Exception {
        when(apiService.adminDtddCaches()).thenReturn(summary(DtddRefetchJobDto.IDLE, List.of(
                new DtddKeyQuotaDto("id-1", "ddd_…mnop", 27, 4120, AT, null),
                new DtddKeyQuotaDto("id-2", "ddd_…2345", 0, 480, AT, AT.plusSeconds(50)),
                new DtddKeyQuotaDto("id-3", "ddd_…zzzz", null, null, null, null))));

        String html = render("/spa/admin/dtdd-caches", Locale.FRENCH);

        assertThat(html).contains("data-state=\"IDLE\"", "Jamais lancé", "id=\"admin-dc-start\"",
                "data-admin-open=\"topics\"", "data-admin-open=\"games\"", "data-admin-open=\"searches\"",
                "4 entrées · 1 périmée · durée de vie 7 j", "3 entrées · aucune périmée · durée de vie 180 j",
                "2 entrées · 2 périmées · durée de vie 12 h", "laissant 500 requêtes du mois",
                "ddd_…mnop", "4120", "pas encore relevé", "au repos jusqu");
        // Only the key under the reserve is flagged
        assertThat(html.split("admin-dc-low", -1)).hasSize(2);
        // The raw description carries its apostrophe as written
        assertThat(html).contains("s&#39;arrête de lui-même avant d&#39;y toucher");
        // Not running: no stop button shown
        String cancel = html.substring(html.indexOf("id=\"admin-dc-cancel\""));
        assertThat(cancel.substring(0, cancel.indexOf('>'))).contains("hidden");
    }

    @Test
    void summary_ofARunningRefetch_showsItsProgressAndItsStop() throws Exception {
        when(apiService.adminDtddCaches()).thenReturn(summary(
                new DtddRefetchJobDto("RUNNING", 9, 4, 1, null, AT, null, AT.plusSeconds(30)), List.of()));

        String html = render("/spa/admin/dtdd-caches", Locale.ENGLISH);

        assertThat(html).contains("data-state=\"RUNNING\"", "Running", "4 / 9 entries · 1 unanswered",
                "Waiting for a key until", "No DTDD key in the pool.", "DTDD''s".replace("''", "&#39;"));
        String start = html.substring(html.indexOf("id=\"admin-dc-start\""));
        assertThat(start.substring(0, start.indexOf('>'))).contains("hidden");
    }

    @Test
    void summary_ofAStoppedRefetch_tellsWhy() throws Exception {
        when(apiService.adminDtddCaches()).thenReturn(summary(
                new DtddRefetchJobDto("STOPPED", 9, 6, 0, "QUOTA", AT, AT.plusSeconds(90), null), List.of()));

        assertThat(render("/spa/admin/dtdd-caches", Locale.FRENCH))
                .contains("Arrêté", "Plus aucune clé ne garde la réserve du mois.", "Fini le");
    }

    @Test
    void eachCache_listsItsEntries_withTheirRefetchAndStaleness() throws Exception {
        when(apiService.adminDtddTopicsCache(any(), anyInt())).thenReturn(new AdminPage<>(List.of(
                new DtddTopicsCacheEntryDto(16758, "Halo Infinite", List.of("a dog dies", "gore"), List.of(),
                        List.of("jump scares"), AT, true)), 0, 1, 1));
        when(apiService.adminDtddGamesCache(any(), anyInt())).thenReturn(new AdminPage<>(List.of(
                new DtddGameCacheEntryDto(16758, "Halo Infinite", "Video Game", null, AT, false)), 0, 1, 1));
        when(apiService.adminDtddSearchesCache(any(), anyInt())).thenReturn(new AdminPage<>(List.of(
                new DtddSearchCacheEntryDto("halo", List.of(16758L, 20311L), AT, false)), 0, 1, 1));

        String topics = render("/spa/admin/dtdd-caches/topics?q=halo", Locale.ENGLISH);
        assertThat(topics).contains("data-kind=\"topics\"", "data-admin-key=\"16758\"", "a dog dies, gore",
                "jump scares", "data-key=\"16758\"", "admin-dc-stale", "value=\"halo\"");

        String games = render("/spa/admin/dtdd-caches/games", Locale.ENGLISH);
        assertThat(games).contains("data-kind=\"games\"", "Video Game", "data-key=\"16758\"")
                .doesNotContain("admin-dc-stale");

        String searches = render("/spa/admin/dtdd-caches/searches", Locale.ENGLISH);
        assertThat(searches).contains("data-admin-key=\"halo\"", "16758, 20311", "data-key=\"halo\"");
    }

    @Test
    void anotherCache_isNotFound() throws Exception {
        mvc.perform(get("/spa/admin/dtdd-caches/keys").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnavailableApi_offersARetry() throws Exception {
        assertThat(render("/spa/admin/dtdd-caches", Locale.ENGLISH)).contains("data-admin-retry");
        assertThat(render("/spa/admin/dtdd-caches/topics", Locale.ENGLISH)).contains("data-admin-retry");
    }

    @Test
    void aTitleInFull_showsItsRecordItsTopicsAndTheSearchesThatFoundIt() throws Exception {
        when(apiService.adminDtddCacheEntry("topics", "16758")).thenReturn(new DtddCacheEntryDetailDto(16758L, null,
                new DtddGameCacheEntryDto(16758, "Halo Infinite", "Video Game", "https://poster.test/h.jpg", AT, false),
                new DtddTopicsCacheEntryDto(16758, "Halo Infinite", List.of("a dog dies", "gore"), List.of(),
                        List.of("jump scares"), AT, true),
                List.of(new DtddSearchCacheEntryDto("halo", List.of(16758L), AT, false)), List.of()));

        String html = render("/spa/admin/dtdd-caches/topics/entry?key=16758", Locale.ENGLISH);

        assertThat(html).contains("Halo Infinite", "https://www.doesthedogdie.com/media/16758", "Video Game",
                "https://poster.test/h.jpg", ">a dog dies<", ">gore<", ">jump scares<", ">halo<", "admin-dc-stale");
        // An empty list of topics reads as such
        assertThat(html).contains(">—<");
    }

    @Test
    void aTitleWithoutTopics_saysSo() throws Exception {
        when(apiService.adminDtddCacheEntry("games", "9")).thenReturn(new DtddCacheEntryDetailDto(9L, null,
                new DtddGameCacheEntryDto(9, "Portal", null, null, AT, false), null, List.of(), List.of()));

        assertThat(render("/spa/admin/dtdd-caches/games/entry?key=9", Locale.FRENCH))
                .contains("Portal", "pas en cache", "Aucune recherche en cache ne l&#39;a trouvé.");
    }

    @Test
    void aSearchInFull_showsTheTitlesItFound() throws Exception {
        when(apiService.adminDtddCacheEntry("searches", "halo")).thenReturn(new DtddCacheEntryDetailDto(null, "halo",
                null, null, List.of(new DtddSearchCacheEntryDto("halo", List.of(16758L, 1L), AT, false)),
                List.of(new DtddCacheEntryDetailDto.FoundTitle(16758L, "Halo Infinite", true),
                        new DtddCacheEntryDetailDto.FoundTitle(1L, null, false))));

        assertThat(render("/spa/admin/dtdd-caches/searches/entry?key=halo", Locale.ENGLISH))
                .contains("Halo Infinite", "topics cached", "not cached", "topics not cached",
                        "https://www.doesthedogdie.com/media/1");
    }

    @Test
    void anEntryGone_saysSo() throws Exception {
        assertThat(render("/spa/admin/dtdd-caches/topics/entry?key=7", Locale.ENGLISH))
                .contains("This entry is no longer in the cache.");
    }

    @Test
    void eachEntry_canBeSeenInFull() throws Exception {
        when(apiService.adminDtddGamesCache(any(), anyInt())).thenReturn(new AdminPage<>(List.of(
                new DtddGameCacheEntryDto(16758, "Halo Infinite", "Video Game", null, AT, false)), 0, 1, 1));

        assertThat(render("/spa/admin/dtdd-caches/games", Locale.ENGLISH))
                .contains("class=\"admin-dc-detail\"", "id=\"admin-dc-dialog\"");
    }
}
