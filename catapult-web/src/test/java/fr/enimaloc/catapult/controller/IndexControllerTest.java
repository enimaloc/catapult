package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Regression coverage for the /channel/{username} route: it was previously broken two
 * ways — the SPA fragment endpoint pointed at the wrong template (pages/channels instead
 * of pages/channel, silently rendering empty content since Thymeleaf's fragment selector
 * doesn't throw on a zero-match selection), and the username path variable was discarded
 * entirely instead of being threaded into the model.
 */
@WebMvcTest(controllers = {IndexController.class, SpaFragmentController.class})
// I18nConfiguration: titles like "{0} - #{app.brand}" need its message source, as in production.
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class IndexControllerTest {

    @Autowired MockMvc mvc;
    @Autowired org.springframework.context.MessageSource messages;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    /**
     * The channel template now dereferences {@code channelPage} on the model, so every
     * test that renders it (even ones not asserting on channelPage itself) needs a
     * non-null stub. lenient() avoids UnnecessaryStubbingException on the test below that
     * overrides this stub with its own value.
     */
    @BeforeEach
    void stubDefaultChannelPage() {
        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), java.util.Set.of(), List.of(), java.util.Set.of(),
                null, null, null, null, null, null, null, "uuid");
        lenient().when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);
        lenient().when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, java.util.Set.of(), null, null, java.util.Set.of(), false, false, false,
                null, null, java.util.Set.of(), List.of(), false, java.util.Set.of(), List.of()));
        lenient().when(apiService.dtddMappingStatus(any())).thenReturn(new DtddMappingStatusDto(null, null, false, null));
    }

    @Test
    void spaChannelFragment_rendersChannelTemplate_notChannelsTemplate() throws Exception {
        mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/channel :: channel"))
                .andExpect(model().attribute("username", "enimaloc"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
    }

    @Test
    void channelFullPage_threadsUsernameIntoModel() throws Exception {
        mvc.perform(get("/channel/enimaloc"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("username", "enimaloc"))
                .andExpect(model().attribute("page", "channel"));
    }

    @Test
    void channelFullPage_putsChannelPageDataInModel() throws Exception {
        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), java.util.Set.of(), List.of(), java.util.Set.of(),
                null, null, null, null, null, null, null, "uuid");
        when(apiService.channelPage(org.mockito.ArgumentMatchers.eq("enimaloc"), anyInt(), any(), any()))
                .thenReturn(data);

        mvc.perform(get("/channel/enimaloc"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("channelPage", data));
    }

    @Test
    void channelFullPage_threadsPageAndFilterParams() throws Exception {
        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(2, 5, 90, List.of()),
                List.of(), java.util.Set.of(), List.of(), java.util.Set.of(),
                "AUTO", null, null, null, null, null, null, "uuid");
        when(apiService.channelPage("enimaloc", 2, "AUTO", null)).thenReturn(data);

        mvc.perform(get("/channel/enimaloc").param("page", "2").param("status", "AUTO"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("channelPage", data));
    }

    /**
     * A moderator viewing a channel they don't own: the page renders without the owner-only
     * parts (integrations, bot), whose model ModelFiller#channel leaves out for them.
     */
    @Test
    void channelFullPage_moderatorViewer_rendersWithoutError() throws Exception {
        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), java.util.Set.of(), List.of(), java.util.Set.of(),
                null, null, null, null, null, null, null, "uuid");
        when(apiService.channelPage(org.mockito.ArgumentMatchers.eq("enimaloc"), anyInt(), any(), any()))
                .thenReturn(data);

        mvc.perform(get("/channel/enimaloc"))
                .andExpect(status().isOk());
    }

    /**
     * spa.js builds its router from this inline JSON: every SpaPage must keep the property
     * names it reads (id, templateUrl, dynamic, titleKey) whatever serializer Thymeleaf picks.
     */
    @Test
    void indexPage_inlinesTheSpaRoutesAsJsonForTheRouter() throws Exception {
        String html = mvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String spa = html.substring(html.indexOf("spa: ") + 5, html.indexOf("titles: "));
        org.assertj.core.api.Assertions.assertThat(spa)
                .contains("\"id\":\"privacy\"")
                .contains("\"templateUrl\":\"/spa/channel\"")
                .contains("\"dynamic\":true")
                .contains("\"titleKey\":\"page.title.landing\"");
    }

    @Test
    void unknownPage_isARealNotFound_keptOutOfSearchEngines() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<meta name=\"robots\" content=\"noindex\">")));
    }

    @Test
    void publicPage_describesItselfWithItsCanonicalUrl() throws Exception {
        String html = mvc.perform(get("/privacy").param("utm_source", "x"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("<meta name=\"description\"")
                .contains("<link rel=\"canonical\" href=\"http://localhost/privacy\">")
                .contains("<meta property=\"og:url\" content=\"http://localhost/privacy\">")
                .doesNotContain("noindex")
                .doesNotContain("application/ld+json");
    }

    @Test
    void landingPage_namesTheSiteForSearchResults() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("<link rel=\"canonical\" href=\"http://localhost/\">")
                .contains("\"@type\": \"WebSite\"");
    }

    @Test
    void channelPage_isKeptOutOfSearchEngines() throws Exception {
        String html = mvc.perform(get("/channel/enimaloc")).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("<meta name=\"robots\" content=\"noindex\">")
                .doesNotContain("rel=\"canonical\"")
                .doesNotContain("<meta name=\"description\"");
    }

    @Test
    void navbar_leavesRoomForTheMembersChannels_withoutWaitingForThem() throws Exception {
        String html = mvc.perform(get("/").sessionAttr(fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY, "jwt"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .containsPattern("<nav id=\"nav-channels\" class=\"nav-channels\" aria-label=\"[^\"]+\"></nav>");
        org.assertj.core.api.Assertions.assertThat(barEnd(html)).doesNotContain("/oauth2/authorization/twitch");
        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).channelList();
    }

    @Test
    void navbar_offersToLogIn_whenLoggedOut() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("nav-channels\"");
        org.assertj.core.api.Assertions.assertThat(barEnd(html)).contains("href=\"/oauth2/authorization/twitch\"");
    }

    @Test
    void navbar_linksToTheAdminPage_forAdminsOnly() throws Exception {
        String key = fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY;
        String member = mvc.perform(get("/").sessionAttr(key, "jwt")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(barEnd(member)).doesNotContain("id=\"nav-admin\"");

        org.mockito.Mockito.when(apiService.isAdmin()).thenReturn(true);
        String admin = mvc.perform(get("/").sessionAttr(key, "jwt")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(barEnd(admin)).containsPattern("<a id=\"nav-admin\" data-link [^>]*href=\"/admin\"");
    }

    /** Read without arguments (admin-tabs.js fills {0} itself), so MessageFormat's '' escape would show up literally. */
    @Test
    void adminErrorMessage_keepsASingleApostrophe_inFrench() {
        org.assertj.core.api.Assertions.assertThat(
                messages.getMessage("admin.error.generic", null, java.util.Locale.FRENCH))
                .isEqualTo("L'action a échoué ({0}).");
    }

    @Test
    void adminPage_rendersItsTabs_forAnAdmin() throws Exception {
        org.mockito.Mockito.when(apiService.isAdmin()).thenReturn(true);
        String key = fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY;

        for (String url : List.of("/admin", "/spa/admin")) {
            String html = mvc.perform(get(url).sessionAttr(key, "jwt")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(html)
                    .contains("id=\"admin-tabs\"")
                    .contains("<mdui-tab value=\"members\"", "<mdui-tab value=\"ccl\"",
                            "<mdui-tab value=\"caches\"", "<mdui-tab value=\"data\"", "<mdui-tab value=\"well-known\"")
                    .doesNotContain("process-rules")
                    // Attribute order is Thymeleaf's business: only check each one is on the ccl panel.
                    .containsPattern("<mdui-tab-panel(?=[^>]*value=\"ccl\")(?=[^>]*data-admin-lazy)(?=[^>]*data-unavailable=\"[^\"]+\")[^>]*>");
        }
    }

    @Test
    void adminPage_listsTheMembers_withNoDeletingOneself() throws Exception {
        org.mockito.Mockito.when(apiService.isAdmin()).thenReturn(true);
        java.util.UUID self = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        java.util.UUID other = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002");
        org.mockito.Mockito.when(apiService.currentUserId()).thenReturn(self);
        org.mockito.Mockito.when(apiService.adminMembers()).thenReturn(new fr.enimaloc.catapult.common.dto.admin.AdminMembersPage(List.of(
                new fr.enimaloc.catapult.common.dto.admin.AdminMemberDto(self, "1", "enimaloc", "img", null, false, true, "ACTIVE",
                        java.time.Instant.parse("2025-03-01T12:00:00Z")),
                new fr.enimaloc.catapult.common.dto.admin.AdminMemberDto(other, "2", "viewer", "img", "765", false, false, "ACTIVE",
                        java.time.Instant.parse("2025-03-01T12:00:00Z"))),
                java.util.Map.of(), false));
        String key = fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY;

        for (String url : List.of("/admin", "/spa/admin")) {
            String html = mvc.perform(get(url).sessionAttr(key, "jwt")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(html)
                    .contains("id=\"admin-members\"", "data-member-id=\"" + self + "\"", "data-member-id=\"" + other + "\"")
                    .containsPattern("data-action=\"delete\" data-confirm=\"[^\"]*\" disabled=\"disabled\"")
                    .contains(">765<");
        }
    }

    @Test
    void adminPage_saysSo_whenTheMembersCantBeLoaded() throws Exception {
        org.mockito.Mockito.when(apiService.isAdmin()).thenReturn(true);
        String key = fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY;

        String html = mvc.perform(get("/spa/admin").sessionAttr(key, "jwt")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("id=\"admin-members\"").contains("name=\"error_outline\"");
    }

    @Test
    void adminPage_isRefused_401LoggedOut_403ToAMember() throws Exception {
        String key = fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY;
        for (String url : List.of("/admin", "/spa/admin")) {
            String loggedOut = mvc.perform(get(url)).andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(loggedOut).doesNotContain("id=\"admin-tabs\"");

            String member = mvc.perform(get(url).sessionAttr(key, "jwt")).andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(member).doesNotContain("id=\"admin-tabs\"");
        }
    }

    /** The navbar's end area (the landing page has login buttons of its own). */
    private static String barEnd(String html) {
        int start = html.indexOf("<div class=\"app-bar-end\">");
        return html.substring(start, html.indexOf("</div>", start));
    }
}
