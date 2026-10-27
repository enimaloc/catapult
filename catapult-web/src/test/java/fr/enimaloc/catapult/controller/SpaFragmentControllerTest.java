package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The fragments spa.js fetches on client-side navigation (the channel one is in IndexControllerTest). */
@WebMvcTest(controllers = SpaFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class SpaFragmentControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    @Test
    void landing() throws Exception {
        mvc.perform(get("/spa/landing"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/landing :: landing"))
                .andExpect(model().attribute("page", ""))
                .andExpect(model().attributeExists("app"));
    }

    @Test
    void privacy_inTheRequestedLanguage() throws Exception {
        mvc.perform(get("/spa/privacy").locale(Locale.FRENCH))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/privacy :: privacy"))
                .andExpect(model().attributeExists("privacy", "lastUpdate"));
    }

    @Test
    void navChannels_listsTheMembersChannels_markingTheLiveOnes() throws Exception {
        when(apiService.channelList()).thenReturn(new ChannelListResponse("1", List.of(
                new ChannelDto(UUID.randomUUID(), "1", "enimaloc", "https://example.test/a.png", true),
                new ChannelDto(UUID.randomUUID(), "2", "other", "https://example.test/b.png", false))));

        String html = mvc.perform(get("/spa/nav-channels").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/nav-channels :: channels"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .containsPattern("class=\"nav-channel live\"\\s+href=\"/channel/enimaloc\"")
                .containsPattern("class=\"nav-channel\"\\s+href=\"/channel/other\"");
    }

    @Test
    void navChannels_areRememberedForTheirLiveUpdates() throws Exception {
        List<ChannelDto> channels = List.of(new ChannelDto(UUID.randomUUID(), "1", "enimaloc", "img", true));
        when(apiService.channelList()).thenReturn(new ChannelListResponse("1", channels));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");

        mvc.perform(get("/spa/nav-channels").session(session));

        verify(navEvents).remember(session, channels);
    }

    @Test
    void navChannels_isEmpty_whenLoggedOut() throws Exception {
        String html = mvc.perform(get("/spa/nav-channels")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).isBlank();
        verify(apiService, never()).channelList();
        verifyNoInteractions(navEvents);
    }

    @Test
    void channels_isGone_theNavbarListsThem() throws Exception {
        mvc.perform(get("/spa/channels")).andExpect(status().isNotFound());
    }

    @Test
    void unknownFragment_isA404ErrorFragment() throws Exception {
        mvc.perform(get("/spa/nope"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("pages/error :: error"))
                .andExpect(model().attribute("errorCode", 404));
    }

    @Test
    void help_rendersTheTitleAndTheTrustedHtmlBody() throws Exception {
        when(apiService.help("status", Locale.FRENCH))
                .thenReturn(new fr.enimaloc.catapult.common.dto.HelpContent("Statut", "<p>Le <b>statut</b></p>"));

        String html = mvc.perform(get("/spa/help/status").locale(Locale.FRENCH))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/help :: content"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Statut").contains("<p>Le <b>statut</b></p>");
    }

    @Test
    void help_unreachable_showsTheCardIdOnly() throws Exception {
        when(apiService.help("status", Locale.ENGLISH)).thenReturn(null);

        String html = mvc.perform(get("/spa/help/status").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("status").doesNotContain("<p>");
    }

    @Test
    void help_badCardId_is404() throws Exception {
        for (String bad : List.of("A", "x_y", "a".repeat(41), "a1")) {
            mvc.perform(get("/spa/help/" + bad)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/spa/help/..%2Fx")).andExpect(status().is4xxClientError());
        verify(apiService, never()).help(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void channelState_isThePagesStateAsItsEvents_forThePagesOwnQuery() throws Exception {
        when(apiService.channelPage("enimaloc", 2, "AUTO", null)).thenReturn(new ChannelPageData(
                new ChannelUserDto("1", "2", "enimaloc", "img"), "enimaloc", true, true, false,
                new GameDto("b1", "Valheim", "STEAM"), new PagedBindings(2, 3, 1, List.of(new BindingDto("b1", "AUTO",
                        "STEAM", "Valheim", "1", "Valheim", false, true, Set.of(), true, false, Set.of()))),
                List.of(), Set.of(), List.of(), Set.of(), "AUTO", null, null, null, null, null, null, "uuid", null));
        when(apiService.dtddMappingStatus("enimaloc")).thenReturn(new DtddMappingStatusDto(
                new DtddMappingCurrentDto(7L, "Valheim", 0.8, true), null, false, "42", false));

        String json = mvc.perform(get("/spa/channel/enimaloc/state").param("page", "2").param("status", "AUTO")
                        .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode state = JsonMapper.builder().build().readTree(json);
        assertThat(java.util.stream.IntStream.range(0, state.size()).mapToObj(i -> state.get(i).get("name").asString()))
                .containsExactly("ChannelLiveStateEvent", "BotStateChangedEvent",
                "GameChangedEvent", "BindingUpdatedEvent", "CclStateEvent", "BindingIgnoredStateEvent",
                "TwEnabledStateEvent", "TwResetEvent", "DtddMappingChangedEvent");
        assertThat(state.get(0).get("data").get("state").asBoolean()).isTrue();
        assertThat(state.get(2).get("data").get("bindingId").asString()).isEqualTo("b1");
        JsonNode dtdd = state.get(8).get("data");
        assertThat(dtdd.get("igdbId").asString()).isEqualTo("42");
        assertThat(dtdd.get("current").get("verified").asBoolean()).isTrue();
        assertThat(dtdd.get("proposed").asBoolean()).isFalse();
        assertThat(dtdd.get("validatable").asBoolean()).isFalse();
    }

    @Test
    void channelState_refusesAVisitor_andAChannelCatapultApiDoesntGive() throws Exception {
        mvc.perform(get("/spa/channel/enimaloc/state")).andExpect(status().isUnauthorized());
        mvc.perform(get("/spa/channel/enimaloc/state").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());
    }
}
