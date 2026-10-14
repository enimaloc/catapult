package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void navChannels_isEmpty_whenLoggedOut() throws Exception {
        String html = mvc.perform(get("/spa/nav-channels")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).isBlank();
        verify(apiService, never()).channelList();
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
}
