package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.Severity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
@Import({ModelFiller.class, SiteMetaTags.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
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

    /** catapult-api answers nothing for a visitor, an unknown channel or one the member may not see. */
    @Test
    void channelPage_withoutData_showsTheErrorPage_insteadOfFailing() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(null);
        String key = ApiClient.SESSION_JWT_KEY;

        mvc.perform(get("/channel/ghost")).andExpect(status().isUnauthorized())
                .andExpect(view().name("index")).andExpect(model().attribute("error", true));
        mvc.perform(get("/spa/channel/ghost")).andExpect(status().isUnauthorized())
                .andExpect(view().name("pages/error :: error"));
        mvc.perform(get("/channel/ghost").sessionAttr(key, "jwt")).andExpect(status().isNotFound())
                .andExpect(view().name("index")).andExpect(model().attribute("error", true));
        mvc.perform(get("/spa/channel/ghost").sessionAttr(key, "jwt")).andExpect(status().isNotFound())
                .andExpect(view().name("pages/error :: error"));
        verify(apiService, never()).channelSettings(any());
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

    @Autowired SiteMetaTags siteMetaTags;

    @Test
    void everyPage_carriesTheAdminsMetaTags_escaped() throws Exception {
        siteMetaTags.clear(); // the context's copy may hold another test's list
        when(apiService.metaTags()).thenReturn(java.util.List.of(
                new fr.enimaloc.catapult.common.dto.admin.MetaTagDto(1, fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.NAME,
                        "google-site-verification", "abc\"><script>", null),
                new fr.enimaloc.catapult.common.dto.admin.MetaTagDto(2, fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY,
                        "og:image", "https://catapult.example/og.png", null)));
        try {
            for (String url : new String[]{"/", "/channel/enimaloc"}) {
                String html = mvc.perform(get(url)).andReturn().getResponse().getContentAsString();
                org.assertj.core.api.Assertions.assertThat(html)
                        .contains("<meta content=\"abc&quot;&gt;&lt;script&gt;\" name=\"google-site-verification\">")
                        .contains("<meta content=\"https://catapult.example/og.png\" property=\"og:image\">")
                        .doesNotContain("<script>\"");
            }
        } finally {
            siteMetaTags.clear();
        }
    }

    @Test
    void anSpaFragment_doesntLoadTheAdminsMetaTags() throws Exception {
        siteMetaTags.clear();
        mvc.perform(get("/spa/landing")).andExpect(status().isOk());
        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).metaTags();
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
    void login_showsTheErrorCatapultApiSent() throws Exception {
        String notWhitelisted = mvc.perform(get("/login").param("error", "not_whitelisted"))
                .andReturn().getResponse().getContentAsString();
        assertThat(notWhitelisted).contains(messages.getMessage("login.error.not_whitelisted", null, java.util.Locale.ENGLISH).replace("'", "&#39;"))
                .contains("href=\"/oauth2/authorization/twitch\"");

        String bare = mvc.perform(get("/login").param("error", "")).andReturn().getResponse().getContentAsString();
        assertThat(bare).contains(messages.getMessage("login.error.generic", null, java.util.Locale.ENGLISH))
                .doesNotContain(messages.getMessage("login.error.not_whitelisted", null, java.util.Locale.ENGLISH));

        String unknown = mvc.perform(get("/login").param("error", "<x>")).andReturn().getResponse().getContentAsString();
        assertThat(unknown).contains(messages.getMessage("login.error.generic", null, java.util.Locale.ENGLISH)).doesNotContain("<x>");

        String none = mvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        assertThat(none).doesNotContain("login-error");
    }

    @Test
    void join_linksTwitchWithAValidCodeOnly() throws Exception {
        String valid = mvc.perform(get("/join").param("invite", "ABC234")).andReturn().getResponse().getContentAsString();
        assertThat(valid).contains("href=\"/oauth2/authorization/twitch?invite=ABC234\"")
                .doesNotContain(messages.getMessage("join.no_code", null, java.util.Locale.ENGLISH));

        for (String bad : new String[]{"abc<x>", "ABC1", "ABCDEFGHJKLMN", "\"onmouseover"}) {
            String html = mvc.perform(get("/join").param("invite", bad)).andReturn().getResponse().getContentAsString();
            assertThat(html).doesNotContain("authorization/twitch?invite=")
                    .contains(messages.getMessage("join.no_code", null, java.util.Locale.ENGLISH));
        }
        String missing = mvc.perform(get("/join")).andReturn().getResponse().getContentAsString();
        assertThat(missing).doesNotContain("authorization/twitch?invite=");
    }

    @Test
    void join_showsTheErrorCatapultApiSent() throws Exception {
        for (String error : new String[]{"invalid_invite", "alpha_full"}) {
            String html = mvc.perform(get("/join").param("error", error)).andReturn().getResponse().getContentAsString();
            assertThat(html).contains(messages.getMessage("join.error." + error, null, java.util.Locale.ENGLISH).replace("'", "&#39;"));
        }
        String generic = mvc.perform(get("/join").param("error", "boom")).andReturn().getResponse().getContentAsString();
        assertThat(generic).contains(messages.getMessage("login.error.generic", null, java.util.Locale.ENGLISH));
    }

    @Test
    void loginAndJoin_sendAMemberToTheirChannel() throws Exception {
        String jwt = "x." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"username\":\"enimaloc\"}".getBytes()) + ".y";
        String key = ApiClient.SESSION_JWT_KEY;
        mvc.perform(get("/login").sessionAttr(key, jwt)).andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/channel/enimaloc"));
        mvc.perform(get("/join").param("invite", "ABC234").sessionAttr(key, jwt)).andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/channel/enimaloc"));
        mvc.perform(get("/spa/login").sessionAttr(key, jwt))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/spa/channel/enimaloc"));
        mvc.perform(get("/spa/join").sessionAttr(key, jwt))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/spa/channel/enimaloc"));
    }

    @Test
    void loginAndJoin_renderForASessionWithoutALogin() throws Exception {
        mvc.perform(get("/login").sessionAttr(ApiClient.SESSION_JWT_KEY, "opaque")).andExpect(status().isOk());
        mvc.perform(get("/join").sessionAttr(ApiClient.SESSION_JWT_KEY, "opaque")).andExpect(status().isOk());
    }

    @Test
    void loginAndJoin_fragmentsRender() throws Exception {
        assertThat(mvc.perform(get("/spa/login").param("error", "not_whitelisted")).andReturn().getResponse().getContentAsString())
                .contains("id=\"login\"");
        assertThat(mvc.perform(get("/spa/join").param("invite", "ABC234")).andReturn().getResponse().getContentAsString())
                .contains("twitch?invite=ABC234");
    }

    @Test
    void loginAndJoin_areNotIndexed() throws Exception {
        for (String path : new String[]{"/login", "/join"}) {
            String html = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("<meta name=\"robots\" content=\"noindex\">")
                    .doesNotContain("rel=\"canonical\"");
        }
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

    @Test
    void navbar_offersToLogOut_forMembersOnly() throws Exception {
        String key = fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY;
        String member = mvc.perform(get("/").sessionAttr(key, "jwt")).andReturn().getResponse().getContentAsString();
        assertThat(barEnd(member))
                .containsPattern("<form id=\"nav-logout\" [^>]*action=\"/logout\" method=\"post\"")
                .containsPattern("<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"");

        String visitor = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(barEnd(visitor)).doesNotContain("nav-logout");
    }

    @Test
    void banner_showsTheLogin_whileImpersonating() throws Exception {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"x\",\"username\":\"alice\"}".getBytes());
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "h." + payload + ".sig");

        String member = mvc.perform(get("/").session(session).locale(java.util.Locale.ENGLISH))
                .andReturn().getResponse().getContentAsString();
        assertThat(member).doesNotContain("impersonation-banner");

        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin");
        String impersonating = mvc.perform(get("/").session(session).locale(java.util.Locale.ENGLISH))
                .andExpect(model().attribute("impersonatedLogin", "alice"))
                .andReturn().getResponse().getContentAsString();
        assertThat(impersonating)
                .contains("Logged in as")
                .contains("<strong>alice</strong>")
                .containsPattern("<form [^>]*action=\"/admin/impersonate/exit\" method=\"post\"");
        int form = impersonating.indexOf("action=\"/admin/impersonate/exit\"");
        assertThat(impersonating.substring(form, impersonating.indexOf("</form>", form)))
                .containsPattern("<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"");
    }

    @Test
    void logout_endsTheSession_andGoesHome() throws Exception {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY, "jwt");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/logout").session(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void logout_needsTheCsrfToken() throws Exception {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY, "jwt");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/logout").session(session))
                .andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void everyDescribedError_hasItsTitleAndDescription() {
        for (java.util.Locale locale : List.of(java.util.Locale.FRENCH, java.util.Locale.ENGLISH)) {
            for (int code : ModelFiller.DESCRIBED_ERRORS) {
                for (String part : List.of("title", "description")) {
                    String key = "error." + code + "." + part;
                    assertThat(messages.getMessage(key, null, null, locale)).as(key + " (" + locale + ")")
                            .isNotNull().isNotEqualTo(key);
                }
            }
        }
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
                            "<mdui-tab value=\"caches\"", "<mdui-tab value=\"data\"", "<mdui-tab value=\"meta\"")
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

    /** The navbar's end area, up to the bar's close (the landing page has login buttons of its own). */
    private static String barEnd(String html) {
        int start = html.indexOf("<div class=\"app-bar-end\">");
        return html.substring(start, html.indexOf("</mdui-top-app-bar>", start));
    }

    private AdminPage<NotificationDto> notificationsPage(int number, int totalPages) {
        var n = new NotificationDto(UUID.randomUUID(), "Hello <b>", "<i>body</i>",
                Severity.INFO, null, null, null, Instant.parse("2026-10-18T10:00:00Z"), false);
        return new AdminPage<>(List.of(n), number, totalPages, 41);
    }

    @Test
    void notificationsFragment_rendersThePage_forAMember() throws Exception {
        var page = notificationsPage(1, 3);
        when(apiService.notifications(1)).thenReturn(page);

        String html = mvc.perform(get("/spa/notifications").param("page", "1").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/notifications :: notifications"))
                .andExpect(model().attribute("notifications", page))
                .andExpect(model().attribute("page", 1))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("id=\"notifications-page\"", "data-notifications-read-all", "Hello &lt;b&gt;", "<i>body</i>",
                        "href=\"/notifications?page=0\"", "href=\"/notifications?page=2\"", "2 / 3",
                        // its own template for live items, should the bell's menu not have loaded
                        "<template id=\"notification-page-item-template\">", "class=\"notification-cta\"");
    }

    @Test
    void notificationsPage_isServedFullAndAsAFragment_withoutBeingSwallowedByTheCatchAlls() throws Exception {
        when(apiService.notifications(0)).thenReturn(notificationsPage(0, 1));

        String html = mvc.perform(get("/notifications").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("page", "notifications"))
                .andExpect(model().attribute("error", false))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"notifications-page\"").doesNotContain("href=\"/notifications?page=");
    }

    @Test
    void notificationsPage_refusesAVisitorWhoIsLoggedOut() throws Exception {
        mvc.perform(get("/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("error", true));
        mvc.perform(get("/spa/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(view().name("pages/error :: error"));
        verify(apiService, never()).notifications(anyInt());
    }

    @Test
    void notificationsPage_saysSo_whenCatapultApiFails() throws Exception {
        when(apiService.notifications(0)).thenReturn(null);

        for (String url : List.of("/notifications", "/spa/notifications")) {
            String html = mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains("id=\"notifications-page-unavailable\"")
                    .doesNotContain("id=\"notifications-page\"");
        }
    }

    @Test
    void notificationsPage_showsItsEmptyState() throws Exception {
        when(apiService.notifications(0))
                .thenReturn(new AdminPage<>(List.of(), 0, 0, 0));
        String html = mvc.perform(get("/spa/notifications").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).containsPattern("class=\"notification-empty\"(?![^>]*hidden)");
    }

    /** No build info (tests, the IDE): the footer shows no version rather than a placeholder. */
    @Test
    void footer_showsNoVersion_withoutBuildInfo() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("class=\"version\"").doesNotContain("TODO");
    }

    @Test
    void settingsTab_saysSo_whenTheSettingsCantBeLoaded() throws Exception {
        when(apiService.channelSettings(any())).thenReturn(null);

        for (String url : List.of("/channel/enimaloc", "/spa/channel/enimaloc")) {
            String html = mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains("id=\"channel-settings-unavailable\"")
                    .doesNotContain("id=\"channel-ccl-settings-form\"");
        }
    }
}
