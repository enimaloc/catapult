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
     * Regression test for a NullPointerException on non-owner viewers (e.g. a moderator
     * viewing a channel they don't own): ModelFiller#channel only fetches
     * channelSettings/dtddMapping when isOwner() is true, so the template
     * must not dereference those attributes unconditionally. Deliberately does not stub
     * channelSettings/dtddMapping — that's the point being verified.
     */
    @Test
    void channelFullPage_nonOwnerViewer_rendersWithoutError() throws Exception {
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

    /** The navbar's end area (the landing page has login buttons of its own). */
    private static String barEnd(String html) {
        int start = html.indexOf("<div class=\"app-bar-end\">");
        return html.substring(start, html.indexOf("</div>", start));
    }
}
