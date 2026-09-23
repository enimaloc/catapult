package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.PagedBindings;
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
@WebMvcTest(controllers = {IndexController.class, IndexController.SPAPages.class})
@Import({ModelFiller.class, WebSecurityConfig.class})
class IndexControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

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
                null, null, false, false, false, false, false, false, false, 15L, false, false, "uuid");
        lenient().when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);
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
                null, null, false, false, false, false, false, false, false, 15L, false, false, "uuid");
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
                "AUTO", null, false, false, false, false, false, false, false, 15L, false, false, "uuid");
        when(apiService.channelPage("enimaloc", 2, "AUTO", null)).thenReturn(data);

        mvc.perform(get("/channel/enimaloc").param("page", "2").param("status", "AUTO"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("channelPage", data));
    }
}
