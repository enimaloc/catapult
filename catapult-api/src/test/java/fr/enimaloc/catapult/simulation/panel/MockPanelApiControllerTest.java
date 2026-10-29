package fr.enimaloc.catapult.simulation.panel;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.GameSourceResolver;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationActions;
import fr.enimaloc.catapult.simulation.SimulationJournal;
import fr.enimaloc.catapult.simulation.SimulationProperties;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import fr.enimaloc.catapult.simulation.account.FictitiousAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * The panel's JSON API on a real {@link SimulationRegistry} and {@link SimulationJournal}: settings one call at a
 * time, the actions handed to {@link SimulationActions}, the journal; only a loopback client reaches it, and a write
 * needs the panel's header.
 */
class MockPanelApiControllerTest {

    private static final String API = "/api/internal/mock";

    @TempDir
    Path dir;

    UserAccountRepository userAccounts = mock(UserAccountRepository.class);
    OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    FictitiousAccountService accounts = mock(FictitiousAccountService.class);
    SimulationActions actions = mock(SimulationActions.class);
    GameStateService gameState = mock(GameStateService.class);
    StreamStateService streamState = mock(StreamStateService.class);
    TwitchEventSubService eventSub = mock(TwitchEventSubService.class);
    GameSourceResolver games = mock(GameSourceResolver.class);
    MockEnvironment env = new MockEnvironment();
    SimulationJournal journal = new SimulationJournal();
    SimulationRegistry registry;
    MockMvc mvc;

    UserAccount alice = account("mock-a1b2c3d4", "alice");
    UserAccount real = account("12345", "enimaloc");

    @BeforeEach
    void setUp() {
        when(userAccounts.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(userAccounts.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(userAccounts.findById(real.getId())).thenReturn(Optional.of(real));
        when(tokens.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        when(tokens.findByUserAndProvider(real, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(new OAuthToken()));
        when(accounts.list()).thenReturn(List.of(alice, real));
        when(accounts.isAdmin(alice.getId())).thenReturn(true);
        when(accounts.account(any(UUID.class))).thenReturn(Optional.empty());
        when(accounts.account(alice.getId())).thenReturn(Optional.of(alice));
        when(accounts.account(real.getId())).thenReturn(Optional.of(real));

        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        registry = new SimulationRegistry(properties, userAccounts, tokens,
                mock(MinecraftFriendLinkRepository.class), mock(MinecraftServiceAccountRepository.class), env);

        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("twitchEventSubService", eventSub);
        mvc = standaloneSetup(new MockPanelApiController(accounts, registry, journal, actions,
                gameState, streamState, beans.getBeanProvider(TwitchEventSubService.class), games, "12345"))
                .build();
    }

    private static UserAccount account(String twitchId, String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(twitchId);
        account.setTwitchUsername(username);
        account.setStatus(UserAccount.Status.ACTIVE);
        account.setBotEnabled(true);
        return account;
    }

    /** A write as the panel sends it: JSON, with its header. */
    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, String json) {
        return builder.header(MockPanelApiController.PANEL_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    @Test
    void api_set_setting_and_read_it_back() throws Exception {
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", alice.getId(), "twitch.helix.update-channel"),
                        "{\"mode\":\"ERROR\",\"httpStatus\":429,\"latencyMs\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ERROR"))
                .andExpect(jsonPath("$.httpStatus").value(429));

        assertThat(registry.setting(alice.getId(), SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL))
                .isEqualTo(CallSetting.error(429).withLatency(250));
        mvc.perform(get(API + "/accounts/{id}/settings", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.update-channel')].mode").value("ERROR"))
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.update-channel')].httpStatus").value(429))
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.update-channel')].latencyMs").value(250))
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.update-channel')].realAllowed").value(false))
                // Only the account's own calls: the global ones are set in the global section
                .andExpect(jsonPath("$[?(@.key=='igdb.search')]").isEmpty());
    }

    @Test
    void api_refuses_real_for_fictitious_400() throws Exception {
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", alice.getId(), "twitch.helix.update-channel"),
                        "{\"mode\":\"REAL\"}"))
                .andExpect(status().isBadRequest());

        assertThat(registry.setting(alice.getId(), SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL))
                .isEqualTo(CallSetting.ok());
    }

    @Test
    void api_refuses_an_error_without_an_http_error_status() throws Exception {
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", alice.getId(), "twitch.helix.update-channel"),
                        "{\"mode\":\"ERROR\",\"httpStatus\":200}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknown_keys_and_accounts_are_404_and_a_global_key_is_not_set_per_account() throws Exception {
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", alice.getId(), "twitch.helix.nope"),
                        "{\"mode\":\"OK\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", UUID.randomUUID(),
                        "twitch.helix.update-channel"), "{\"mode\":\"OK\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", alice.getId(), "igdb.search"),
                        "{\"mode\":\"OK\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put(API + "/global/settings/{key}", "twitch.helix.update-channel"), "{\"mode\":\"OK\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void global_settings_are_set_and_read_once_for_everyone() throws Exception {
        mvc.perform(write(put(API + "/global/settings/{key}", "igdb.search"),
                        "{\"mode\":\"TIMEOUT\",\"latencyMs\":1000}"))
                .andExpect(status().isOk());

        assertThat(registry.setting(null, SimulatedCalls.IGDB_SEARCH)).isEqualTo(CallSetting.timeout().withLatency(1000));
        mvc.perform(get(API + "/global/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='igdb.search')].mode").value("TIMEOUT"))
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.update-channel')]").isEmpty());
    }

    /** The refresh the registry forces REAL shows as REAL, flagged forced, whatever it is set to. */
    @Test
    void a_forced_refresh_shows_as_real_and_forced() throws Exception {
        registry.set(real.getId(), SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL, CallSetting.real());

        mvc.perform(get(API + "/accounts/{id}/settings", real.getId()))
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.refresh-token')].mode").value("REAL"))
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.refresh-token')].forced").value(true))
                .andExpect(jsonPath("$[?(@.key=='twitch.helix.update-channel')].forced").value(false));
    }

    /** The real subscriptions follow an EventSub event that turns REAL or stops being REAL. */
    @Test
    void eventsub_mode_change_reconnects() throws Exception {
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", real.getId(), "twitch.eventsub.stream-online"),
                        "{\"mode\":\"REAL\"}"))
                .andExpect(status().isOk());
        verify(eventSub).connect(real);

        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", real.getId(), "twitch.eventsub.stream-online"),
                        "{\"mode\":\"OK\"}"))
                .andExpect(status().isOk());
        verify(eventSub, org.mockito.Mockito.times(2)).connect(real);
    }

    @Test
    void eventsub_change_that_stays_simulated_does_not_reconnect() throws Exception {
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", real.getId(), "twitch.eventsub.stream-online"),
                        "{\"mode\":\"ERROR\",\"httpStatus\":500}"))
                .andExpect(status().isOk());
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", real.getId(), "twitch.helix.update-channel"),
                        "{\"mode\":\"REAL\"}"))
                .andExpect(status().isOk());

        verify(eventSub, never()).connect(any());
    }

    /** A bot that is off has no connection to follow the setting: the next bot start connects as set. */
    @Test
    void eventsub_change_of_a_paused_bot_does_not_connect() throws Exception {
        real.setBotEnabled(false);

        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", real.getId(), "twitch.eventsub.stream-online"),
                        "{\"mode\":\"REAL\"}"))
                .andExpect(status().isOk());

        verify(eventSub, never()).connect(any());
    }

    @Test
    void api_play_calls_simulation_actions() throws Exception {
        mvc.perform(write(post(API + "/accounts/{id}/play", alice.getId()),
                        "{\"provider\":\"STEAM\",\"sourceId\":\"892970\",\"name\":\"Valheim\"}"))
                .andExpect(status().isNoContent());

        verify(actions).play(alice, GetterConfig.Provider.STEAM, "892970", "Valheim");
    }

    @Test
    void play_refused_by_the_actions_is_a_400() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("This account has no Steam id"))
                .when(actions).play(any(), any(), any(), any());

        mvc.perform(write(post(API + "/accounts/{id}/play", alice.getId()),
                        "{\"provider\":\"STEAM\",\"sourceId\":\"892970\",\"name\":\"Valheim\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(API + "/accounts/{id}/play", alice.getId()), "{\"provider\":\"STEAM\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void the_other_actions_go_to_simulation_actions() throws Exception {
        mvc.perform(write(post(API + "/accounts/{id}/live", alice.getId()), "{\"live\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(write(post(API + "/accounts/{id}/bot", alice.getId()), "{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(write(post(API + "/accounts/{id}/stop-playing", alice.getId()), "{}"))
                .andExpect(status().isNoContent());
        mvc.perform(write(post(API + "/accounts/{id}/manual-category", alice.getId()),
                        "{\"categoryId\":\"27471\",\"categoryName\":\"Minecraft\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(write(post(API + "/accounts/{id}/raid-out", alice.getId()), "{\"toLogin\":\"bob\"}"))
                .andExpect(status().isNoContent());

        verify(actions).setLive(alice, true);
        verify(actions).setBot(alice, false);
        verify(actions).stopPlaying(alice);
        verify(actions).manualCategoryChange(alice, "27471", "Minecraft");
        verify(actions).raidOut(alice, "bob");
    }

    @Test
    void a_real_account_other_than_the_owner_is_no_admin() throws Exception {
        real.setTwitchId("67890");

        mvc.perform(get(API + "/accounts"))
                .andExpect(jsonPath("$[1].admin").value(false));
    }

    @Test
    void a_picked_game_is_resolved_by_catapult() throws Exception {
        when(games.resolve(GetterConfig.Provider.STEAM, "Valheim"))
                .thenReturn(new GameSourceResolver.Resolution("892970", "Valheim", null));
        when(games.resolve(GetterConfig.Provider.XBOX, "Valheim"))
                .thenReturn(new GameSourceResolver.Resolution(null, "Valheim", "IGDB lists no Microsoft Store id"));

        mvc.perform(get(API + "/games/resolve").queryParam("provider", "STEAM").queryParam("name", " Valheim "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value("892970"))
                .andExpect(jsonPath("$.reason").doesNotExist());
        mvc.perform(get(API + "/games/resolve").queryParam("provider", "XBOX").queryParam("name", "Valheim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").doesNotExist())
                .andExpect(jsonPath("$.reason").value("IGDB lists no Microsoft Store id"));
        mvc.perform(get(API + "/games/resolve").queryParam("provider", "STEAM").queryParam("name", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void games_are_searched_through_the_actions() throws Exception {
        when(actions.searchGames(alice, "val")).thenReturn(List.of(new TwitchCategory("1", "Valheim", "box")));

        mvc.perform(get(API + "/accounts/{id}/games", alice.getId()).queryParam("q", "val"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Valheim"));
    }

    @Test
    void state_shows_the_channel_as_catapult_and_the_simulation_see_it() throws Exception {
        registry.update(alice.getId(), data -> data.withLive(true).withCategory("27471", "Minecraft"));
        when(streamState.isLive(alice)).thenReturn(true);
        when(gameState.getLastKnownGame(alice)).thenReturn(Optional.of(
                new DetectedGame("892970", GameBinding.SourceType.STEAM, "Valheim")));

        mvc.perform(get(API + "/accounts/{id}/state", alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.botEnabled").value(true))
                .andExpect(jsonPath("$.detectedGame.name").value("Valheim"))
                .andExpect(jsonPath("$.categoryName").value("Minecraft"))
                .andExpect(jsonPath("$.data.live").value(true))
                .andExpect(jsonPath("$.data.installed[0].id").value("sim-ext-panel"));
    }

    @Test
    void accounts_are_listed_with_their_badges() throws Exception {
        registry.update(alice.getId(), data -> data.withModeratedChannelIds(List.of("12345")));

        mvc.perform(get(API + "/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].fictitious").value(true))
                .andExpect(jsonPath("$[0].admin").value(true))
                .andExpect(jsonPath("$[0].moderates[0]").value("enimaloc"))
                .andExpect(jsonPath("$[1].fictitious").value(false))
                // The real account app.owner-id names is the admin, as its login makes it
                .andExpect(jsonPath("$[1].admin").value(true));
    }

    @Test
    void fictitious_accounts_are_created_and_deleted() throws Exception {
        when(accounts.create("bob", false, List.of(real.getId()), true)).thenReturn(account("mock-0000beef", "bob"));

        mvc.perform(write(post(API + "/accounts"),
                        "{\"username\":\"bob\",\"admin\":false,\"moderates\":[\"" + real.getId()
                                + "\"],\"botEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.fictitious").value(true));
        mvc.perform(delete(API + "/accounts/{id}", alice.getId()).header(MockPanelApiController.PANEL_HEADER, "1"))
                .andExpect(status().isNoContent());
        verify(accounts).delete(alice.getId());

        when(accounts.create(eq("alice"), any(Boolean.class), any(), any(Boolean.class)))
                .thenThrow(new IllegalArgumentException("The username alice is taken"));
        mvc.perform(write(post(API + "/accounts"), "{\"username\":\"alice\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulated_data_is_updated_field_by_field() throws Exception {
        mvc.perform(write(put(API + "/accounts/{id}/data", alice.getId()),
                        "{\"steamProfilePublic\":false,\"moderatedChannelIds\":[\"12345\"],"
                                + "\"installed\":[{\"can_activate\":true,\"id\":\"e1\",\"name\":\"Ext\","
                                + "\"type\":[\"panel\"],\"version\":\"2.0\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steamProfilePublic").value(false));

        var data = registry.data(alice.getId());
        assertThat(data.steamProfilePublic()).isFalse();
        assertThat(data.moderatedChannelIds()).containsExactly("12345");
        assertThat(data.installed()).singleElement().satisfies(e -> assertThat(e.id()).isEqualTo("e1"));
        // Untouched fields keep their value
        assertThat(data.currentCategoryName()).isEqualTo("Just Chatting");
    }

    @Test
    void the_catalogue_is_grouped_by_api() throws Exception {
        mvc.perform(get(API + "/calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].api").value("twitch.helix"))
                .andExpect(jsonPath("$[0].calls[0].key").value("twitch.helix.update-channel"))
                .andExpect(jsonPath("$[0].calls[0].scope").value("ACCOUNT"))
                .andExpect(jsonPath("$[?(@.api=='igdb')].calls[0].scope").value("GLOBAL"));
    }

    @Test
    void journal_lists_entries() throws Exception {
        journal.record(new SimulationJournal.Entry(Instant.now(), alice.getId(), "twitch.helix.update-channel",
                "PATCH /channels", "OK", "ok", 0));
        journal.record(new SimulationJournal.Entry(Instant.now(), null, "igdb.search", "search valheim", "REAL",
                "ok", 0));

        mvc.perform(get(API + "/journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(API + "/journal").queryParam("account", alice.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].request").value("PATCH /channels"));
    }

    @Test
    void the_journal_streams() throws Exception {
        mvc.perform(get(API + "/journal/stream"))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getRequest().isAsyncStarted()).isTrue());
    }

    @Test
    void api_is_404_for_a_remote_client() throws Exception {
        mvc.perform(get(API + "/accounts").header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isNotFound());
        mvc.perform(write(put(API + "/accounts/{id}/settings/{key}", alice.getId(), "twitch.helix.update-channel"),
                        "{\"mode\":\"ERROR\",\"httpStatus\":429}")
                        .with(r -> {
                            r.setRemoteAddr("203.0.113.7");
                            return r;
                        }))
                .andExpect(status().isNotFound());

        assertThat(registry.setting(alice.getId(), SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL))
                .isEqualTo(CallSetting.ok());
    }

    /**
     * A page of another origin can post a form or a plain request to the loopback, but cannot add a header without
     * a CORS preflight, which nothing allows: a write without the panel's header is refused.
     */
    @Test
    void writes_without_the_panel_header_are_refused() throws Exception {
        mvc.perform(post(API + "/accounts/{id}/stop-playing", alice.getId()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(API + "/accounts/{id}", alice.getId()))
                .andExpect(status().isForbidden());

        verify(actions, never()).stopPlaying(any());
        verify(accounts, never()).delete(any());
    }
}
