package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminCclDto;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin game mappings tab as it renders: both paged lists, the form's choices and its labels in each language. */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminGameMappingsRenderingTest {

    private static final UUID IGDB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OVERRIDE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean BroadcastState broadcastState;

    @BeforeEach
    void stubTheLists() {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCcl()).thenReturn(new AdminCclPage(List.of(
                new AdminCclDto("violent-graphic", "Violent and graphic depictions", null, List.of()),
                new AdminCclDto("gambling", "Gambling", null, List.of())), List.of()));
        when(apiService.adminTws()).thenReturn(List.of(
                new TwDefinitionDto("violence", "Violence", null, true, 1),
                new TwDefinitionDto("retired", "Retired warning", null, false, 2)));
        when(apiService.adminGameMappings(0)).thenReturn(new AdminPage<>(List.of(
                new GameMappingDto(IGDB_ID, "STEAM", "730", "Counter-Strike 2", "32399", "Counter-Strike",
                        null, null, 14, Instant.parse("2026-10-22T10:00:00Z"), "enimaloc"),
                new GameMappingDto(OVERRIDE_ID, "XBOX", "9NBLGGH4PNC7", "Halo Infinite", "506416", "Halo Infinite",
                        Set.of("violent-graphic"), Set.of(), 6, Instant.parse("2026-10-23T10:00:00Z"), "alice")),
                0, 1, 2));
        when(apiService.adminMissingGameMappings(0)).thenReturn(new AdminPage<>(List.of(
                new DetectedGameDto("STEAM", "1245620", "Elden Ring", 12),
                new DetectedGameDto("STEAM", "413150", "Stardew Valley", 5)), 0, 2, 3));
    }

    private String render(Locale locale) throws Exception {
        return mvc.perform(get("/spa/admin/game-mappings").locale(locale).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void listsMappingsWithIgdbOrOverrides() throws Exception {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("data-gm-edit=\"" + IGDB_ID + "\"", "data-gm-delete=\"" + IGDB_ID + "\"",
                "data-gm-edit=\"" + OVERRIDE_ID + "\"", "Counter-Strike 2", "9NBLGGH4PNC7", "enimaloc", "alice");
        // IGDB's CCLs and TWs: told as such, and sent back as such (no override on the row)
        String igdbRow = html.substring(html.indexOf("data-id=\"" + IGDB_ID + "\""), html.indexOf("data-id=\"" + OVERRIDE_ID + "\""));
        assertThat(igdbRow).contains(">IGDB<").doesNotContain("data-ccls-override", "data-tws-override");
        // An override shows the CCL's name, an empty one "None", and both are kept on the row for the form
        String overrideRow = html.substring(html.indexOf("data-id=\"" + OVERRIDE_ID + "\""));
        assertThat(overrideRow).contains("Violent and graphic depictions", ">None<",
                "data-ccls-override=\"true\" data-ccls=\"violent-graphic\"", "data-tws-override=\"true\"",
                "data-twitch-game-id=\"506416\"");
        // Each row's binding count
        assertThat(igdbRow).contains(">14<");
    }

    @Test
    void igdbColumn_showsNameAndId_theIdAlone_orNothing_andEscapesIgdbText() throws Exception {
        UUID named = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID idOnly = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID none = UUID.fromString("00000000-0000-0000-0000-000000000013");
        UUID hostile = UUID.fromString("00000000-0000-0000-0000-000000000014");
        Instant at = Instant.parse("2026-10-22T10:00:00Z");
        when(apiService.adminGameMappings(0)).thenReturn(new AdminPage<>(List.of(
                new GameMappingDto(named, "BATTLENET", "WoW", null, "18122", "World of Warcraft", null, null, 0, at,
                        "a", false, "123", "World of Warcraft"),
                new GameMappingDto(idOnly, "BATTLENET", "Hero", null, "32959", "Heroes of the Storm", null, null, 0, at,
                        "a", false, "7313", null),
                new GameMappingDto(none, "STEAM", "730", null, "32399", "Counter-Strike", null, null, 0, at, "a"),
                new GameMappingDto(hostile, "STEAM", "9", null, "1", "One", null, null, 0, at,
                        "a", false, "5", "<b>x</b>")), 0, 1, 4));

        String html = render(Locale.ENGLISH);

        assertThat(html).contains("IGDB game", ">World of Warcraft (123)<", ">7313<", "data-igdb-id=\"123\"",
                "data-igdb-game-name=\"World of Warcraft\"");
        String noneRow = html.substring(html.indexOf("data-id=\"" + none + "\""), html.indexOf("data-id=\"" + hostile + "\""));
        assertThat(noneRow).doesNotContain("data-igdb-id").contains("admin-gm-igdb\" data-label=\"IGDB game\"></span>");
        // IGDB's text only ever reaches the page escaped
        assertThat(html).doesNotContain("<b>x</b>").contains("&lt;b&gt;x&lt;/b&gt; (5)");
    }

    @Test
    void form_hasTheIgdbPicker_andTheCategoryHint() throws Exception {
        String en = render(Locale.ENGLISH);
        String fr = render(Locale.FRENCH);

        assertThat(en).contains("name=\"igdbGame\"", "admin-gm-igdb-results", "admin-gm-igdb-clear",
                "Deduced from IGDB when empty", "Clear the IGDB game");
        assertThat(fr).doesNotContain("??").contains("Jeu IGDB", "Déduite d&#39;IGDB si vide", "Retirer le jeu IGDB");
    }

    @Test
    void missingList_isPagedWithCreateButtons() throws Exception {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("Elden Ring", "Stardew Valley",
                "data-gm-create data-source-type=\"STEAM\" data-source-id=\"1245620\"",
                "data-gm-missing-page=\"1\"");
        // The mappings fit on one page: no pager of their own
        assertThat(html).doesNotContain("data-gm-page=");
        // The page shown is kept for reloads after an action
        assertThat(html).contains("data-page=\"0\"", "data-missing-page=\"0\"");
    }

    @Test
    void form_hasTheFourPlatforms_notManual() throws Exception {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("id=\"admin-gm-dialog\"", "id=\"admin-gm-delete-dialog\"",
                "<mdui-menu-item value=\"STEAM\"", "<mdui-menu-item value=\"XBOX\"",
                "<mdui-menu-item value=\"BATTLENET\"", "<mdui-menu-item value=\"MINECRAFT\"");
        assertThat(html).doesNotContain("MANUAL");
        // The CCLs and the enabled TWs the streamers choose from, to replace IGDB's
        assertThat(html).contains("class=\"admin-gm-ccl\" value=\"gambling\"", "class=\"admin-gm-tw\" value=\"violence\"")
                .doesNotContain("value=\"retired\"");
    }

    @Test
    void labels_areTranslated() throws Exception {
        String fr = render(Locale.FRENCH);
        String en = render(Locale.ENGLISH);

        assertThat(fr).doesNotContain("??").contains("Mappings de jeu", "Mappings manquants", "Créer le mapping",
                "Appliquer aux streams en cours", "Remplacer ceux d&#39;IGDB", "Aucun");
        assertThat(en).doesNotContain("??").contains("Game mappings", "Missing mappings", "Create the mapping",
                "Apply to the streams live now", "Replace IGDB&#39;s", "None");
    }

    @Test
    void countLabels_readRightForAnyCount() throws Exception {
        String fr = render(Locale.FRENCH);
        String en = render(Locale.ENGLISH);

        // Each count after its label, so "1" and "0" read as well as "2" (filled in by the script)
        assertThat(en).contains(
                "data-label-result=\"Bindings updated: {bindings} · Live channels updated: {live} · Failures: {failed}\"",
                "data-label-suggestion=\"{name} — {id} · Streamers: {n}\"");
        assertThat(fr).contains(
                "data-label-result=\"Associations mises à jour : {bindings} · Chaînes en live mises à jour : {live} · Échecs : {failed}\"",
                "data-label-suggestion=\"{name} — {id} · Streamers : {n}\"");
    }
}
