package fr.enimaloc.catapult.controller.mock;

import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        var binding = data.getPrimaryBinding();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("live", String.valueOf(data.getChannelDto().live()));
        fields.put("avatarUrl", data.getChannelDto().profileImageUrl());
        fields.put("detectedSourceType", data.getGameDto().sourceType());
        fields.put("detectedSourceName", data.getGameDto().sourceName());
        fields.put("hasSteamProvider", "true");
        fields.put("hasSteam", String.valueOf(data.isHasSteam()));
        fields.put("hasXboxProvider", "true");
        fields.put("hasXbox", String.valueOf(data.isHasXbox()));
        fields.put("steamProfileCacheTtlMinutes", "15");
        fields.put("bindingStatus", binding.status());
        fields.put("bindingSourceType", binding.sourceType());
        fields.put("bindingSourceName", binding.sourceName());
        fields.put("bindingTwitchGameId", binding.twitchGameId());
        fields.put("bindingTwitchGameName", binding.twitchGameName());
        fields.put("bindingCclEnabled", String.valueOf(binding.cclEnabled()));
        fields.put("bindingTwEnabled", String.valueOf(binding.twEnabled()));
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
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
                .andExpect(model().attribute("binding", data.getPrimaryBinding()))
                .andExpect(model().attribute("sourceTypes", MockPresets.SOURCE_TYPES))
                .andExpect(model().attribute("availableTws", MockPresets.AVAILABLE_TWS));
    }

    @Test
    void edit_rendersASessionWithoutBindingsToo() throws Exception {
        data.deleteBinding(data.getPrimaryBinding().id());

        mvc.perform(get("/mock/admin")).andExpect(status().isOk()).andExpect(model().attribute("binding", (Object) null));
    }

    @Test
    void withoutASession_goesBackToTheMockLogin() throws Exception {
        when(mockApiService.getData()).thenReturn(null);

        mvc.perform(get("/mock/admin")).andExpect(redirectedUrl("/oauth2/authorization/twitch"));
        mvc.perform(form()).andExpect(redirectedUrl("/oauth2/authorization/twitch"));
    }

    @Test
    void apply_unchangedForm_publishesNothing() throws Exception {
        mvc.perform(form()).andExpect(redirectedUrl("/mock/admin"));

        assertThat(eventCount()).isZero();
    }

    @Test
    void apply_liveChange_isPublished() throws Exception {
        boolean live = !data.getChannelDto().live();

        mvc.perform(form("live", String.valueOf(live)));

        assertThat(data.getChannelDto().live()).isEqualTo(live);
        assertThat(events.stream(ChannelLiveStateEvent.class)).containsExactly(new ChannelLiveStateEvent(username, live));
    }

    @Test
    void apply_detectedGameChange_pointsAtTheMatchingBinding() throws Exception {
        var binding = data.getPrimaryBinding();
        data.setDetectedGame("XBOX", "Something else");

        mvc.perform(form("detectedSourceType", binding.sourceType().toLowerCase(),
                "detectedSourceName", binding.sourceName().toLowerCase()));

        assertThat(events.stream(GameChangedEvent.class)).containsExactly(
                new GameChangedEvent(username, binding.id(), binding.sourceType(), binding.sourceName()));
        assertThat(data.getGameDto().sourceName()).isEqualTo(binding.sourceName().toLowerCase());
    }

    @Test
    void apply_detectedGameWithoutBinding_isPublishedWithoutOne() throws Exception {
        mvc.perform(form("detectedSourceType", "XBOX", "detectedSourceName", "Halo"));

        assertThat(events.stream(GameChangedEvent.class)).containsExactly(new GameChangedEvent(username, null, null, null));
        assertThat(data.getGameDto().sourceName()).isEqualTo("Halo");
    }

    @Test
    void apply_steamDiagnosticsChange_isPublished() throws Exception {
        mvc.perform(form("steamRateLimited", "true", "steamProfileCacheTtlMinutes", "30"));

        assertThat(events.stream(SteamConnectionStateEvent.class))
                .containsExactly(new SteamConnectionStateEvent(username, data.isHasSteam(), true, false, false));
        assertThat(data.isSteamRateLimited()).isTrue();
        assertThat(data.getSteamProfileCacheTtlMinutes()).isEqualTo(30L);
    }

    @Test
    void apply_replacesThePrimaryBindingAndProviders() throws Exception {
        mvc.perform(form("hasXbox", "true", "bindingStatus", "MANUAL", "bindingCcls", "Gore,Drugs",
                "bindingTws", "spiders", "avatarUrl", "https://img"));

        assertThat(data.isHasXbox()).isTrue();
        assertThat(data.getPrimaryBinding().status()).isEqualTo("MANUAL");
        assertThat(data.getPrimaryBinding().ccls()).containsExactlyInAnyOrder("Gore", "Drugs");
        assertThat(data.getPrimaryBinding().tws()).containsExactly("spiders");
        assertThat(data.getChannelDto().profileImageUrl()).isEqualTo("https://img");
    }
}
