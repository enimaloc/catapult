package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.BindingDto;
import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.GameDto;
import fr.enimaloc.catapult.common.dto.MinecraftData;
import fr.enimaloc.catapult.common.dto.PagedBindings;
import fr.enimaloc.catapult.common.dto.UserSettingsDto;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the per-row correctness of .binding-tw-reset-btn's spa:if: each binding
 * row must carry its own hasOverride state independently of the others, since
 * Visibility.apply on the client is scoped per-row precisely to avoid one binding's
 * update leaking into another's reset button.
 */
@WebMvcTest(controllers = {IndexController.class, IndexController.SPAPages.class})
@Import({ModelFiller.class, WebSecurityConfig.class})
class ChannelVisibilityRenderingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    /**
     * The channel template dereferences channelSettings unconditionally once isOwner()
     * is true (see IndexControllerTest), so every owner-rendering test needs this stubbed.
     */
    @BeforeEach
    void stubOwnerOnlyAttributes() {
        lenient().when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of()));
        lenient().when(apiService.dtddMappingStatus(any())).thenReturn(new DtddMappingStatusDto(null, null, false, null));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void bindingTwResetButton_carriesIndependentVisibilityPerRow() throws Exception {
        BindingDto overridden = new BindingDto(
                "binding-1", "AUTO", "STEAM", "Game A", null, "Game A",
                false, false, Set.of(), true, true, Set.of("spoiler"));
        BindingDto notOverridden = new BindingDto(
                "binding-2", "AUTO", "STEAM", "Game B", null, "Game B",
                false, false, Set.of(), true, false, Set.of());

        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(0, 1, 2, List.of(overridden, notOverridden)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, "uuid");
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Both rows must carry the data-if breadcrumb, but only the
        // non-overridden row's button should also have picked up the 'hidden' class —
        // a shared/collapsed state between rows would make these counts diverge.
        assertThat(countOccurrences(html, "data-if=\"hasOverride\"")).isEqualTo(2);
        assertThat(countOccurrences(html, "binding-tw-reset-btn hidden")).isEqualTo(1);
        assertThat(countOccurrences(html, "binding-tw-reset-btn")).isEqualTo(2);
        // Each row also needs its own data-on breadcrumb for the generic SSE dispatcher
        // (Visibility.dispatch) to re-evaluate hasOverride per row without any JS change.
        assertThat(countOccurrences(html, "data-on=\"hasOverride:TwUpdatedEvent.tws,hasOverride:!TwResetEvent\"")).isEqualTo(2);
    }

    private ChannelPageData channelPageData(boolean isLive) {
        return channelPageData(isLive, null);
    }

    private ChannelPageData channelPageData(boolean isLive, MinecraftData minecraft) {
        return new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, isLive, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, minecraft, null, "uuid");
    }

    /** Extracts the opening tag text for the first element with the given id. */
    private static String openingTagForId(String html, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[a-zA-Z0-9-]+[^>]*\\bid=\"" + id + "\"[^>]*>")
                .matcher(html);
        return m.find() ? m.group() : null;
    }

    @Test
    void streamStateChips_live_showsLiveHidesOffline() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String liveTag = openingTagForId(html, "channel-state-live");
        String offlineTag = openingTagForId(html, "channel-state-offline");
        assertThat(liveTag).contains("data-if=\"live\"").contains("data-on=\"live:ChannelLiveStateEvent.state\"").doesNotContain("hidden");
        assertThat(offlineTag).contains("data-if=\"offline\"").contains("data-on=\"offline:ChannelLiveStateEvent.!state\"").contains("hidden");
    }

    @Test
    void streamStateChips_offline_showsOfflineHidesLive() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(false));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String liveTag = openingTagForId(html, "channel-state-live");
        String offlineTag = openingTagForId(html, "channel-state-offline");
        assertThat(liveTag).contains("data-if=\"live\"").contains("hidden");
        assertThat(offlineTag).contains("data-if=\"offline\"").doesNotContain("hidden");
    }

    private ChannelPageData channelPageDataWithGame(GameDto currentGame) {
        return new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, currentGame,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, "uuid");
    }

    @Test
    void currentGame_gameDetected_showsGameHidesNoGameMessage() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any()))
                .thenReturn(channelPageDataWithGame(new GameDto("binding-1", "Jusant", "STEAM")));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String gameTag = openingTagForId(html, "channel-current-game");
        String noGameTag = openingTagForId(html, "channel-no-game");
        assertThat(gameTag).contains("data-if=\"hasGame\"").doesNotContain("hidden");
        assertThat(noGameTag).contains("data-if=\"noGame\"").contains("hidden");
        assertThat(html).contains(">Jusant<");
    }

    @Test
    void currentGame_noGameDetected_showsNoGameMessageHidesGame() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any()))
                .thenReturn(channelPageDataWithGame(null));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String gameTag = openingTagForId(html, "channel-current-game");
        String noGameTag = openingTagForId(html, "channel-no-game");
        assertThat(gameTag).contains("data-if=\"hasGame\"").contains("hidden");
        assertThat(noGameTag).contains("data-if=\"noGame\"").doesNotContain("hidden");
    }

    @Test
    void spaValue_rendersDataValueForPropertySyncs() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Pins the two spa:value cases that have no spa:if of their own to anchor on —
        // a page-level checkbox sync (#channel-bot-toggle) and a text sync
        // (#channel-current-game), both resolved purely from data-value.
        String botToggleTag = openingTagForId(html, "channel-bot-toggle");
        assertThat(botToggleTag).contains("data-value=\"checked:BotStateChangedEvent.state\"");

        String gameTag = openingTagForId(html, "channel-current-game");
        assertThat(gameTag).contains("data-value=\"textContent:GameChangedEvent.sourceName\"");
    }

    @Test
    void minecraftStatusPending_showsPendingCaseAndCheckButtonOnly() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any()))
                .thenReturn(channelPageData(true, new MinecraftData("PENDING", "Steve123", null)));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Only the PENDING case div and the check button should render visible; every
        // other status div/button carries its own unique flag name (not a shared one —
        // see the ledger/commit for the bug that caused when they weren't) and ends up
        // hidden.
        assertThat(countOccurrences(html, "data-if=\"casePending\"")).isEqualTo(1);
        assertThat(openingTagForId(html, "minecraft-enroll-btn")).contains("hidden");
        assertThat(openingTagForId(html, "minecraft-check-btn")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "minecraft-disconnect-btn")).contains("hidden");
    }
}
