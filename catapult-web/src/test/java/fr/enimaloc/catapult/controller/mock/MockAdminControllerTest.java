package fr.enimaloc.catapult.controller.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.mock.MockApiService;
import fr.enimaloc.catapult.service.mock.MockData;
import fr.enimaloc.catapult.service.mock.MockPresets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = MockAdminController.class)
@Import(WebSecurityConfig.class)
@ActiveProfiles("mock")
@RecordApplicationEvents
class MockAdminControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationEvents events;
    @MockitoBean MockApiService mockApiService;

    private MockData data;
    private String username;

    @BeforeEach
    void setUp() {
        data = MockData.fromJwt("{}");
        username = data.getChannelDto().twitchUsername();
        when(mockApiService.getData()).thenReturn(data);
    }

    /** The admin form as submitted when nothing was changed, with {@code overrides} applied. */
    private MockHttpServletRequestBuilder form(String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (data.getChannelDto().live()) fields.put("live", "on");
        if (data.isBotEnabled()) fields.put("botEnabled", "on");
        fields.put("avatarUrl", data.getChannelDto().profileImageUrl());
        fields.put("detectedSourceType", data.getGameDto().sourceType());
        fields.put("detectedSourceName", data.getGameDto().sourceName());
        fields.put("hasSteamProvider", "on");
        if (data.isHasSteam()) fields.put("hasSteam", "on");
        fields.put("hasXboxProvider", "on");
        if (data.isHasXbox()) fields.put("hasXbox", "on");
        if (data.isHasMinecraftProvider()) fields.put("hasMinecraftProvider", "on");
        fields.put("minecraftStatus", data.getMinecraftLink() == null ? "NONE" : data.getMinecraftLink().status());
        fields.put("steamProfileCacheTtlMinutes", "15");
        List<BindingDto> bindings = data.getBinding();
        for (int i = 0; i < bindings.size(); i++) {
            BindingDto b = bindings.get(i);
            String p = "bindings[" + i + "].";
            fields.put(p + "id", b.id());
            fields.put(p + "status", b.status());
            fields.put(p + "sourceType", b.sourceType());
            fields.put(p + "sourceName", b.sourceName());
            fields.put(p + "twitchGameId", b.twitchGameId());
            fields.put(p + "twitchGameName", b.twitchGameName());
            if (b.ignored()) fields.put(p + "ignored", "on");
            if (b.cclEnabled()) fields.put(p + "cclEnabled", "on");
            if (b.twEnabled()) fields.put(p + "twEnabled", "on");
            if (b.twOverride()) fields.put(p + "twOverride", "on");
            if (!b.ccls().isEmpty()) fields.put(p + "ccls", String.join(",", b.ccls()));
            if (!b.tws().isEmpty()) fields.put(p + "tws", String.join(",", b.tws()));
        }
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) fields.remove(overrides[i]);
            else fields.put(overrides[i], overrides[i + 1]);
        }
        MockHttpServletRequestBuilder request = post("/mock/admin").with(csrf());
        fields.forEach((name, value) -> request.param(name, value.split(",")));
        return request;
    }

    private long eventCount() {
        return events.stream(ChannelUpdatedEvent.class).count();
    }

    @Test
    void edit_rendersTheSessionsState() throws Exception {
        mvc.perform(get("/mock/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("mock/admin"))
                .andExpect(model().attribute("channel", data.getChannelDto()))
                .andExpect(model().attribute("game", data.getGameDto()))
                .andExpect(model().attribute("bindings", data.getBinding()))
                .andExpect(model().attribute("sourceTypes", MockPresets.SOURCE_TYPES))
                .andExpect(model().attribute("availableTws", MockPresets.AVAILABLE_TWS));
    }

    @Test
    void edit_rendersASessionWithoutBindingsToo() throws Exception {
        data.deleteBinding(data.getPrimaryBinding().id());

        mvc.perform(get("/mock/admin")).andExpect(status().isOk()).andExpect(model().attribute("bindings", List.of()));
    }

    @Test
    void withoutASession_goesBackToTheMockLogin() throws Exception {
        when(mockApiService.getData()).thenReturn(null);

        mvc.perform(get("/mock/admin")).andExpect(redirectedUrl("/oauth2/authorization/twitch"));
        mvc.perform(form()).andExpect(redirectedUrl("/oauth2/authorization/twitch"));
    }

    @Test
    void apply_whatEventsCarry_leavesTheAppAlone() throws Exception {
        mvc.perform(form("live", data.getChannelDto().live() ? null : "on", "detectedSourceType", "XBOX",
                        "detectedSourceName", "Halo", "steamRateLimited", "on"))
                .andExpect(flash().attributeCount(0));
    }

    @Test
    void apply_whatNoEventCarries_marksTheAppStale() throws Exception {
        mvc.perform(form("avatarUrl", "https://img")).andExpect(flash().attribute("appStale", true));
        mvc.perform(form("hasXbox", data.isHasXbox() ? null : "on")).andExpect(flash().attribute("appStale", true));
        mvc.perform(form("steamProfileCacheTtlMinutes", "30")).andExpect(flash().attribute("appStale", true));
        mvc.perform(form().param("addBinding", "true")).andExpect(flash().attribute("appStale", true));
        mvc.perform(form("bindings[0].delete", "on")).andExpect(flash().attribute("appStale", true));
    }

    @Test
    void apply_unchangedForm_publishesNothing() throws Exception {
        List<BindingDto> before = data.getBinding();

        mvc.perform(form()).andExpect(redirectedUrl("/mock/admin")).andExpect(flash().attributeCount(0));

        assertThat(eventCount()).isZero();
        assertThat(data.getBinding()).isEqualTo(before);
        verify(mockApiService, never()).dispatchTwitchatEvent(anyString(), anyMap());
        verify(mockApiService, never()).toggleBot(anyString());
    }

    @Test
    void apply_liveChange_isPublished() throws Exception {
        boolean live = !data.getChannelDto().live();

        mvc.perform(form("live", live ? "on" : null));

        assertThat(data.getChannelDto().live()).isEqualTo(live);
        assertThat(events.stream(ChannelLiveStateEvent.class)).containsExactly(new ChannelLiveStateEvent(username, live));
    }

    @Test
    void apply_detectedGameChange_pointsAtTheMatchingBinding() throws Exception {
        var binding = data.getPrimaryBinding();
        data.setDetectedGame(null, "XBOX", "Something else");

        mvc.perform(form("detectedSourceType", binding.sourceType().toLowerCase(),
                "detectedSourceName", binding.sourceName().toLowerCase()));

        assertThat(events.stream(GameChangedEvent.class)).containsExactly(new GameChangedEvent(username, binding.id(),
                binding.sourceType().toLowerCase(), binding.sourceName().toLowerCase()));
        assertThat(data.getGameDto().sourceName()).isEqualTo(binding.sourceName().toLowerCase());
        assertThat(data.getGameDto().bindingId()).isEqualTo(binding.id());
    }

    @Test
    void apply_detectedGameWithoutBinding_isPublishedWithoutOne() throws Exception {
        mvc.perform(form("detectedSourceType", "XBOX", "detectedSourceName", "Halo"));

        assertThat(events.stream(GameChangedEvent.class)).containsExactly(new GameChangedEvent(username, null, "XBOX", "Halo"));
        assertThat(data.getGameDto().sourceName()).isEqualTo("Halo");
        assertThat(data.getGameDto().bindingId()).isNull();
    }

    @Test
    void apply_steamDiagnosticsChange_isPublished() throws Exception {
        mvc.perform(form("steamRateLimited", "on", "steamProfileCacheTtlMinutes", "30"));

        assertThat(events.stream(SteamConnectionStateEvent.class))
                .containsExactly(new SteamConnectionStateEvent(username, data.isHasSteam(), true, false, false));
        assertThat(data.isSteamRateLimited()).isTrue();
        assertThat(data.getSteamProfileCacheTtlMinutes()).isEqualTo(30L);
    }

    @Test
    void apply_editsEveryBinding_andDropsTheDeletedOnes() throws Exception {
        data.addBinding();
        String second = data.getBinding().get(1).id();

        mvc.perform(form("hasXbox", "on", "avatarUrl", "https://img",
                "bindings[0].status", "MANUAL", "bindings[0].ccls", "Gore,Drugs", "bindings[0].tws", "spiders",
                "bindings[1].sourceName", "Halo", "bindings[1].delete", null));

        assertThat(data.isHasXbox()).isTrue();
        assertThat(data.getChannelDto().profileImageUrl()).isEqualTo("https://img");
        assertThat(data.getPrimaryBinding().status()).isEqualTo("MANUAL");
        assertThat(data.getPrimaryBinding().ccls()).containsExactlyInAnyOrder("Gore", "Drugs");
        assertThat(data.getPrimaryBinding().tws()).containsExactly("spiders");
        assertThat(data.getBinding().get(1)).extracting(BindingDto::id, BindingDto::sourceName).containsExactly(second, "Halo");

        mvc.perform(form("bindings[1].delete", "on"));
        assertThat(data.getBinding()).extracting(BindingDto::id).doesNotContain(second);
    }

    @Test
    void apply_withAddBinding_savesThenAppendsABlankBinding() throws Exception {
        int before = data.getBinding().size();

        mvc.perform(form("avatarUrl", "https://img").param("addBinding", "true")).andExpect(redirectedUrl("/mock/admin"));

        assertThat(data.getBinding()).hasSize(before + 1);
        assertThat(data.getChannelDto().profileImageUrl()).isEqualTo("https://img");
    }

    @Test
    void apply_forcesTheSteamTokenAndTheMinecraftLink() throws Exception {
        mvc.perform(form("hasSteamPersonalToken", "on", "steamTokenShared", "on", "hasMinecraftProvider", "on",
                "minecraftStatus", "INVITE_REJECTED", "minecraftName", "Steve", "minecraftServiceAccount", "bot"));

        assertThat(data.isHasSteamPersonalToken()).isTrue();
        assertThat(data.isSteamTokenShared()).isTrue();
        assertThat(data.isHasMinecraftProvider()).isTrue();
        assertThat(data.getMinecraftLink()).isEqualTo(new LinkStateResponse("INVITE_REJECTED", "Steve", "bot"));
        assertThat(events.stream(MinecraftSyncEvent.class)).containsExactly(new MinecraftSyncEvent(username, "INVITE_REJECTED", "Steve"));
    }

    @Test
    void apply_goingLive_sendsStreamStarted_andTheBotSwitchTogglesIt() throws Exception {
        data.setChannelLive(false);

        mvc.perform(form("live", "on", "botEnabled", data.isBotEnabled() ? null : "on"));

        verify(mockApiService).dispatchTwitchatEvent("STREAM_STARTED", Map.of());
        verify(mockApiService).toggleBot(username);
    }

    @Test
    void apply_gameWithACategory_sendsTheCategoryChange_elseNoGameFound() throws Exception {
        var binding = data.getPrimaryBinding();
        data.setBotEnabled(true);
        data.setDetectedGame(null, "XBOX", "Something else");

        mvc.perform(form("detectedSourceType", binding.sourceType(), "detectedSourceName", binding.sourceName()));
        verify(mockApiService).dispatchTwitchatEvent("CATEGORY_CHANGED_BY_CATAPULT",
                Map.of("gameName", binding.twitchGameName(), "previousGameName", "Something else"));

        mvc.perform(form("detectedSourceType", "XBOX", "detectedSourceName", "Halo"));
        verify(mockApiService).dispatchTwitchatEvent("NO_GAME_DETECTED", Map.of());
    }

    @Test
    void sendTwitchatEvent_sendsItsPayloads_andSaysHowMany() throws Exception {
        when(mockApiService.dispatchTwitchatEvent("BOT_ENABLED", Map.of("gameName", "A", "previousGameName", ""))).thenReturn(2);

        mvc.perform(post("/mock/admin/twitchat").with(csrf()).param("event", "BOT_ENABLED").param("gameName", "A"))
                .andExpect(redirectedUrl("/mock/admin"))
                .andExpect(flash().attribute("twitchatResult", "2 payload(s) envoyé(s) à Twitchat."));
        mvc.perform(post("/mock/admin/twitchat").with(csrf()).param("event", "NOPE"))
                .andExpect(flash().attribute("twitchatResult", "Événement inconnu : NOPE"));
        mvc.perform(post("/mock/admin/twitchat").with(csrf()).param("event", "STREAM_STARTED"))
                .andExpect(flash().attribute("twitchatResult", "Rien envoyé : aucun payload actif pour cet événement."));
    }
}
