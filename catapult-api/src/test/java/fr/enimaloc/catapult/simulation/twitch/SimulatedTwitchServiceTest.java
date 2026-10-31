package fr.enimaloc.catapult.simulation.twitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.InjectedEvents;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationJournal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulatedTwitchServiceTest {

    @TempDir
    Path dir;

    SimulationFixture fx;
    TwitchServiceImpl realTwitch = mock(TwitchServiceImpl.class);
    TwitchCategoryServiceImpl realCategories = mock(TwitchCategoryServiceImpl.class);
    RestClient restClient = mock(RestClient.class);
    TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    UserSettingsRepository settings = mock(UserSettingsRepository.class);
    BotToggleService botToggle = mock(BotToggleService.class);
    CatapultCategoryChangeStateService categoryChangeState = mock(CatapultCategoryChangeStateService.class);
    SimulatedTwitchService service;
    UserAccount alice;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
        alice = fx.fictitious;
        when(settings.findById(any())).thenReturn(Optional.empty());
        service = service(new ChannelUpdateEcho(fx.registry, fx.journal, SimulationFixture.providerOf(null)));
    }

    private SimulatedTwitchService service(ChannelUpdateEcho echo) {
        SimulatedTwitchTokenService tokenService =
                new SimulatedTwitchTokenService(fx.tokens, encryption, restClient, fx.runner);
        SimulatedTwitchCategoryService categories =
                new SimulatedTwitchCategoryService(SimulationFixture.providerOf(realCategories), fx.runner);
        return new SimulatedTwitchService(SimulationFixture.providerOf(realTwitch), fx.runner, fx.registry,
                categories, tokenService, fx.tokens, fx.accounts, settings, botToggle, categoryChangeState, echo);
    }

    /** The real EventSub handling behind the echo, over mocks, with the simulation's gate. */
    private TwitchEventSubService eventSub(TwitchatEventDispatcher twitchat) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("simulationGate", new SimulationGate(fx.registry));
        return new TwitchEventSubService(fx.tokens, fx.accounts, mock(TwitchTokenService.class),
                mock(StreamStateService.class), mock(ApplicationEventPublisher.class), RestClient.create(),
                new ObjectMapper(), twitchat, categoryChangeState, DebugRecording.OFF,
                beans.getBeanProvider(SimulationGate.class));
    }

    @Test
    void catapult_change_echo_reaches_on_category_changed_by_catapult() {
        TwitchatEventDispatcher twitchat = mock(TwitchatEventDispatcher.class);
        SimulatedTwitchService echoing = service(new ChannelUpdateEcho(fx.registry, fx.journal,
                SimulationFixture.providerOf(eventSub(twitchat))));
        // The marker the real service keeps: the echo consumes it
        when(categoryChangeState.consumeIfMatches(alice, "33214"))
                .thenReturn(Optional.of(new CatapultCategoryChangeStateService.SelfChange("509658")));

        assertThat(echoing.setCategory(alice, "33214", "Fortnite")).isEqualTo(ChannelUpdateOutcome.APPLIED);

        verify(categoryChangeState).recordCatapultChangeAndReturnPrevious(alice, "33214");
        verify(twitchat, timeout(2000))
                .onCategoryChangedByCatapult(alice, "33214", "Fortnite", "509658", "Just Chatting");
        verify(twitchat, never()).onCategoryChangedManually(any(), any(), any(), any());
        assertThat(fx.journaledKeys()).contains(SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE.id());
    }

    @Test
    void no_echo_injected_when_channel_update_is_real() {
        UserAccount real = fx.real;
        fx.set(real, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE, CallSetting.real());
        TwitchatEventDispatcher twitchat = mock(TwitchatEventDispatcher.class);
        SimulatedTwitchService echoing = service(new ChannelUpdateEcho(fx.registry, fx.journal,
                SimulationFixture.providerOf(eventSub(twitchat))));

        assertThat(echoing.setCategory(real, "33214", "Fortnite")).isEqualTo(ChannelUpdateOutcome.APPLIED);

        verify(twitchat, after(500).never()).onCategoryChangedByCatapult(any(), any(), any(), any(), any());
        verify(twitchat, never()).onCategoryChangedManually(any(), any(), any(), any());
        verify(categoryChangeState, never()).consumeIfMatches(any(), any());
        assertThat(fx.journaledKeys()).doesNotContain(SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE.id());
    }

    @Test
    void an_echo_in_error_or_timeout_is_dropped() {
        TwitchatEventDispatcher twitchat = mock(TwitchatEventDispatcher.class);
        SimulatedTwitchService echoing = service(new ChannelUpdateEcho(fx.registry, fx.journal,
                SimulationFixture.providerOf(eventSub(twitchat))));
        when(categoryChangeState.consumeIfMatches(any(), any()))
                .thenReturn(Optional.of(new CatapultCategoryChangeStateService.SelfChange("509658")));

        fx.set(alice, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE, CallSetting.error(500));
        assertThat(echoing.setCategory(alice, "33214", "Fortnite")).isEqualTo(ChannelUpdateOutcome.APPLIED);
        verify(twitchat, after(600).never()).onCategoryChangedByCatapult(any(), any(), any(), any(), any());

        fx.set(alice, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE, CallSetting.timeout());
        assertThat(echoing.setCategory(alice, "21779", "League of Legends")).isEqualTo(ChannelUpdateOutcome.APPLIED);
        verify(twitchat, after(600).never()).onCategoryChangedByCatapult(any(), any(), any(), any(), any());

        // Twitch took the change: the simulated channel follows it, only the notification is lost
        assertThat(fx.registry.data(alice.getId()).currentCategoryId()).isEqualTo("21779");
        assertThat(fx.journal.recent(alice.getId()))
                .filteredOn(entry -> entry.key().equals(SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE.id()))
                .extracting(SimulationJournal.Entry::outcome)
                .containsExactly("dropped (http 500)", "dropped (timeout)");
    }

    @Test
    void an_echo_waits_its_latency() {
        TwitchatEventDispatcher twitchat = mock(TwitchatEventDispatcher.class);
        SimulatedTwitchService echoing = service(new ChannelUpdateEcho(fx.registry, fx.journal,
                SimulationFixture.providerOf(eventSub(twitchat))));
        when(categoryChangeState.consumeIfMatches(alice, "33214"))
                .thenReturn(Optional.of(new CatapultCategoryChangeStateService.SelfChange("509658")));
        fx.set(alice, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE, CallSetting.ok().withLatency(800));

        echoing.setCategory(alice, "33214", "Fortnite");

        // The DELAY (150 ms) and the latency (800 ms) both come first
        verify(twitchat, after(500).never()).onCategoryChangedByCatapult(any(), any(), any(), any(), any());
        verify(twitchat, timeout(3000))
                .onCategoryChangedByCatapult(alice, "33214", "Fortnite", "509658", "Just Chatting");
        assertThat(fx.journal.recent(alice.getId()))
                .filteredOn(entry -> entry.key().equals(SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE.id()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.mode()).isEqualTo(InjectedEvents.INJECTED);
                    assertThat(entry.latencyMs()).isEqualTo(800);
                });
    }

    private static GameBinding binding(String gameId, String gameName, Set<String> ccls) {
        GameBinding binding = new GameBinding();
        binding.setStatus(GameBinding.Status.MANUAL);
        binding.setTwitchGameId(gameId);
        binding.setTwitchGameName(gameName);
        binding.setCcls(ccls);
        return binding;
    }

    @Test
    void ok_update_channel_never_reaches_helix_and_tracks_the_category() {
        ChannelUpdateOutcome outcome =
                service.updateChannelReporting(alice, binding("32982", "Grand Theft Auto V", Set.of("ViolentGraphic")));

        assertThat(outcome).isEqualTo(ChannelUpdateOutcome.APPLIED);
        assertThat(fx.registry.data(alice.getId()).currentCategoryId()).isEqualTo("32982");
        assertThat(fx.registry.data(alice.getId()).currentCategoryName()).isEqualTo("Grand Theft Auto V");
        assertThat(fx.registry.data(alice.getId()).ccls()).containsExactly("ViolentGraphic");
        assertThat(fx.journaledKeys()).containsExactly("twitch.helix.update-channel");
        verify(categoryChangeState).recordCatapultChangeAndReturnPrevious(alice, "32982");
        verifyNoInteractions(realTwitch, restClient);
    }

    @Test
    void real_mode_delegates() {
        UserAccount real = fx.real;
        GameBinding binding = binding("32982", "Grand Theft Auto V", Set.of());
        when(realTwitch.updateChannelReporting(real, binding)).thenReturn(ChannelUpdateOutcome.FAILED);
        fx.set(real, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL, CallSetting.real());

        assertThat(service.updateChannelReporting(real, binding)).isEqualTo(ChannelUpdateOutcome.FAILED);

        verify(realTwitch).updateChannelReporting(real, binding);
        // A real call is journaled as REAL
        assertThat(fx.journal.recent(null)).extracting(e -> e.key() + " " + e.mode())
                .containsExactly("twitch.helix.update-channel REAL");
        assertThat(fx.registry.data(real.getId()).currentCategoryId()).isEqualTo("509658");
    }

    @Test
    void error_401_on_update_channel_behaves_like_the_real_client() {
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL, CallSetting.error(401));

        ChannelUpdateOutcome outcome = service.updateChannelReporting(alice, binding("32982", "GTA V", Set.of()));

        // the token refreshed (simulated OK), the PATCH retried once, refused again: the bot is paused
        assertThat(outcome).isEqualTo(ChannelUpdateOutcome.FAILED);
        assertThat(fx.journaledKeys()).containsExactly("twitch.helix.update-channel", "twitch.helix.refresh-token",
                "twitch.helix.update-channel");
        verify(botToggle).pauseAfterATokenFailure(alice);
        assertThat(fx.registry.data(alice.getId()).currentCategoryId()).isEqualTo("509658");
        verifyNoInteractions(realTwitch, restClient, categoryChangeState);
    }

    @Test
    void token_refresh_error_pauses_the_bot() {
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL, CallSetting.error(401));
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN, CallSetting.error(400));

        assertThat(service.updateChannelReporting(alice, binding("32982", "GTA V", Set.of())))
                .isEqualTo(ChannelUpdateOutcome.FAILED);

        // no retry once the refresh failed
        assertThat(fx.journaledKeys()).containsExactly("twitch.helix.update-channel", "twitch.helix.refresh-token");
        verify(botToggle).pauseAfterATokenFailure(alice);
        verifyNoInteractions(realTwitch, restClient);
    }

    @Test
    void a_401_on_set_category_is_thrown_once_the_bot_is_paused() {
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY, CallSetting.error(401));
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN, CallSetting.timeout());

        assertThatThrownBy(() -> service.setCategory(alice, "32982", "GTA V"))
                .hasMessageContaining("401");

        verify(botToggle).pauseAfterATokenFailure(alice);
    }

    @Test
    void other_errors_follow_each_method_s_contract() {
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY, CallSetting.error(503));
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_RESET_TO_DEFAULT, CallSetting.timeout());
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_MODERATED_CHANNELS, CallSetting.error(500));
        UserSettings userSettings = new UserSettings();
        userSettings.setNoGameTwitchGameId("509658");
        when(settings.findById(alice.getId())).thenReturn(Optional.of(userSettings));

        assertThatThrownBy(() -> service.setCategory(alice, "32982", "GTA V"))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(service.resetToDefault(alice)).isEqualTo(ChannelUpdateOutcome.FAILED);
        assertThat(service.getModeratedChannelIds(alice)).isEmpty();
        verifyNoInteractions(botToggle, realTwitch);
    }

    @Test
    void a_disabled_bot_skips_the_category_and_refuses_the_extensions() {
        alice.setBotEnabled(false);

        assertThat(service.setCategory(alice, "32982", "GTA V")).isEqualTo(ChannelUpdateOutcome.SKIPPED);
        assertThatThrownBy(() -> service.setActiveExtensions(alice, TwitchExtensionPlacement.update()
                .panel(1, TwitchExtensionPlacement.Slot.of("sim-ext-panel", "1.0.0")).build()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fx.journaledKeys()).isEmpty();
    }

    @Test
    void reset_puts_the_channel_on_its_default_category() {
        UserSettings userSettings = new UserSettings();
        userSettings.setNoGameTwitchGameId("26936");
        userSettings.setNoGameTwitchGameName("Music");
        userSettings.setNoGameCcls(Set.of("ProfanityVulgarity"));
        when(settings.findById(alice.getId())).thenReturn(Optional.of(userSettings));
        fx.registry.update(alice.getId(), data -> data.withCcls(Set.of("ViolentGraphic")));

        assertThat(service.resetToDefault(alice)).isEqualTo(ChannelUpdateOutcome.APPLIED);

        assertThat(fx.registry.data(alice.getId()).currentCategoryName()).isEqualTo("Music");
        assertThat(fx.registry.data(alice.getId()).ccls()).containsExactly("ProfanityVulgarity");
    }

    @Test
    void moderated_channels_come_from_the_simulated_data() {
        fx.registry.update(alice.getId(), data -> data.withModeratedChannelIds(List.of("111", "222")));

        assertThat(service.getModeratedChannelIds(alice)).containsExactly("111", "222");
    }

    @Test
    void extensions_round_trip_through_simulated_data() {
        assertThat(service.getUserExtensions(alice)).extracting(TwitchExtension::id)
                .containsExactly("sim-ext-panel", "sim-ext-overlay", "sim-ext-component");

        TwitchExtensionPlacement after = service.setActiveExtensions(alice, TwitchExtensionPlacement.update()
                .panel(2, TwitchExtensionPlacement.Slot.of("sim-ext-panel", "1.0.0"))
                .component(1, TwitchExtensionPlacement.Slot.at("sim-ext-component", "1.0.0", 10, 20))
                .build());

        assertThat(after.panel().second().id()).isEqualTo("sim-ext-panel");
        assertThat(after.panel().second().name()).isEqualTo("Simulated panel");
        assertThat(after.panel().first().active()).isFalse();
        assertThat(after.overlay().first().active()).isFalse();
        assertThat(after.component().first().x()).isEqualTo(10);
        assertThat(service.getActiveExtensions(alice)).isEqualTo(after);
        assertThat(fx.registry.data(alice.getId()).active()).isEqualTo(after);

        // a later update only changes the slots it carries
        service.setActiveExtensions(alice, TwitchExtensionPlacement.update()
                .panel(2, TwitchExtensionPlacement.Slot.empty()).build());
        TwitchExtensionPlacement cleared = service.getActiveExtensions(alice);
        assertThat(cleared.panel().second().active()).isFalse();
        assertThat(cleared.component().first().id()).isEqualTo("sim-ext-component");
        verifyNoInteractions(realTwitch);
    }

    @Test
    void activating_an_extension_that_is_not_installed_answers_400() {
        assertThatThrownBy(() -> service.setActiveExtensions(alice, TwitchExtensionPlacement.update()
                .panel(1, TwitchExtensionPlacement.Slot.of("not-installed", "1.0.0")).build()))
                .isInstanceOfSatisfying(HttpClientErrorException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(fx.registry.data(alice.getId()).active()).isEqualTo(TwitchExtensionPlacement.empty());
    }

    @Test
    void a_real_failure_is_rethrown_as_is_without_a_simulated_refresh() {
        UserAccount real = fx.real;
        fx.set(real, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY, CallSetting.real());
        HttpClientErrorException unauthorized = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "401",
                HttpHeaders.EMPTY, new byte[0], null);
        when(realTwitch.setCategory(real, "32982", "GTA V")).thenThrow(unauthorized);

        assertThatThrownBy(() -> service.setCategory(real, "32982", "GTA V")).isSameAs(unauthorized);

        // Journaled as a real call that failed, and no simulated refresh follows it
        assertThat(fx.journal.recent(null)).extracting(e -> e.key() + " " + e.mode() + " " + e.outcome())
                .containsExactly("twitch.helix.set-category REAL http 401");
        verify(botToggle, never()).pauseAfterATokenFailure(any());
        verifyNoInteractions(restClient);
    }

    @Test
    void a_failed_extension_write_is_thrown_and_a_failed_read_answers_unreadable() {
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_SET_EXTENSIONS, CallSetting.timeout());
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_ACTIVE_EXTENSIONS, CallSetting.error(500));
        fx.set(alice, SimulatedCalls.TWITCH_HELIX_USER_EXTENSIONS, CallSetting.error(401));

        assertThatThrownBy(() -> service.setActiveExtensions(alice, TwitchExtensionPlacement.update()
                .panel(1, TwitchExtensionPlacement.Slot.of("sim-ext-panel", "1.0.0")).build()))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(service.getActiveExtensions(alice)).isEqualTo(TwitchExtensionPlacement.unreadable());
        assertThat(service.getUserExtensions(alice)).isEmpty();
        verify(botToggle, never()).pauseAfterATokenFailure(any());
    }

    @Test
    void a_category_id_is_found_for_any_name_and_stays_stable() {
        Optional<String> known = service.findCategoryIdByName(alice, "Just Chatting");
        Optional<String> other = service.findCategoryIdByName(alice, "Some Indie Game");

        assertThat(known).contains("509658");
        assertThat(other).isPresent().isEqualTo(service.findCategoryIdByName(alice, "some indie game"));
        verifyNoInteractions(realTwitch, realCategories);
    }
}
