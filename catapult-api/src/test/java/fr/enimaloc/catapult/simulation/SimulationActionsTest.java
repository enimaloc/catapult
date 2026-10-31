package fr.enimaloc.catapult.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.simulation.SimulatedData.SimulatedPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The panel's actions go through the real EventSub handling and the real detection, never around them. */
class SimulationActionsTest {

    @TempDir
    Path dir;

    UserAccountRepository accounts = mock(UserAccountRepository.class);
    OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TwitchatEventDispatcher twitchat = mock(TwitchatEventDispatcher.class);
    CatapultCategoryChangeStateService selfChanges = mock(CatapultCategoryChangeStateService.class);
    SchedulerService scheduler = mock(SchedulerService.class);
    GetterConfigRepository getterConfigs = mock(GetterConfigRepository.class);
    BotToggleService botToggle = mock(BotToggleService.class);
    TwitchService twitch = mock(TwitchService.class);
    StreamStateService streamState = new StreamStateService(mock(ChannelEventPublisher.class), twitchat);
    SimulationJournal journal = new SimulationJournal();

    SimulationRegistry registry;
    TwitchEventSubService eventSub;
    SimulationActions actions;
    UserAccount user = new UserAccount();
    /** A real account, with a Twitch token: its events can be set to REAL. */
    UserAccount realUser = new UserAccount();

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        user.setTwitchId("mock-alice");
        user.setTwitchUsername("alice");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        realUser.setId(UUID.randomUUID());
        realUser.setTwitchId("12345");
        realUser.setTwitchUsername("Bob");
        when(accounts.findById(realUser.getId())).thenReturn(Optional.of(realUser));
        when(tokens.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        when(tokens.findByUserAndProvider(realUser, OAuthToken.Provider.TWITCH))
                .thenReturn(Optional.of(new OAuthToken()));
        when(getterConfigs.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        when(getterConfigs.findByUserOrderByPriorityAsc(user)).thenReturn(List.of());
        when(selfChanges.consumeIfMatches(any(), any())).thenReturn(Optional.empty());

        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        registry = new SimulationRegistry(properties, accounts, tokens, mock(MinecraftFriendLinkRepository.class),
                mock(MinecraftServiceAccountRepository.class), new MockEnvironment());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("simulationGate", new SimulationGate(registry));
        eventSub = new TwitchEventSubService(tokens, accounts, mock(TwitchTokenService.class), streamState,
                publisher, RestClient.create(), new ObjectMapper(), twitchat, selfChanges, DebugRecording.OFF,
                beans.getBeanProvider(SimulationGate.class));
        beans.addBean("twitchEventSubService", eventSub);
        actions = new SimulationActions(registry, journal, beans.getBeanProvider(TwitchEventSubService.class),
                scheduler, getterConfigs, botToggle, twitch);
    }

    private List<ApplicationEvent> published() {
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        return events.getAllValues();
    }

    @Test
    void set_live_publishes_stream_online_through_the_real_handler() {
        actions.setLive(user, true);

        assertThat(streamState.isLive(user)).isTrue();
        assertThat(published()).filteredOn(StreamOnlineEvent.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((StreamOnlineEvent) e).getUser()).isEqualTo(user));
        assertThat(registry.data(user.getId()).live()).isTrue();
        assertThat(journal.recent(user.getId())).extracting(SimulationJournal.Entry::key)
                .containsExactly(SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE.id());
    }

    @Test
    void set_live_is_refused_while_its_event_is_real() {
        registry.set(realUser.getId(), SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE, CallSetting.real());

        assertThatIllegalStateException().isThrownBy(() -> actions.setLive(realUser, true))
                .withMessageContaining("is Real: the real event arrives");

        assertThat(registry.data(realUser.getId()).live()).isFalse();
        assertThat(streamState.isLive(realUser)).isFalse();
        assertThat(journal.recent(realUser.getId())).isEmpty();
    }

    @Test
    void set_live_in_error_drops_the_event() {
        registry.set(user.getId(), SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE, CallSetting.error(503));

        actions.setLive(user, true);

        // Twitch has the stream live, but its notification never arrived
        assertThat(registry.data(user.getId()).live()).isTrue();
        assertThat(streamState.isLive(user)).isFalse();
        assertThat(journal.recent(user.getId())).singleElement().satisfies(entry -> {
            assertThat(entry.mode()).isEqualTo("ERROR");
            assertThat(entry.outcome()).isEqualTo("dropped (http 503)");
        });
    }

    @Test
    void set_live_in_timeout_drops_the_event() {
        registry.set(user.getId(), SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE, CallSetting.timeout());

        actions.setLive(user, true);

        assertThat(streamState.isLive(user)).isFalse();
        assertThat(journal.recent(user.getId())).extracting(SimulationJournal.Entry::outcome)
                .containsExactly("dropped (timeout)");
    }

    @Test
    void set_live_waits_its_latency_before_delivering() {
        registry.set(user.getId(), SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE, CallSetting.ok().withLatency(150));

        long start = System.nanoTime();
        actions.setLive(user, true);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(140);
        assertThat(streamState.isLive(user)).isTrue();
        assertThat(journal.recent(user.getId())).singleElement().satisfies(entry -> {
            assertThat(entry.mode()).isEqualTo(InjectedEvents.INJECTED);
            assertThat(entry.latencyMs()).isEqualTo(150);
        });
    }

    @Test
    void an_event_carries_the_login_in_lower_case_or_the_twitch_id() {
        assertThat(InjectedEvents.login(realUser)).isEqualTo("bob");
        realUser.setTwitchUsername(null);
        assertThat(InjectedEvents.login(realUser)).isEqualTo("12345");
    }

    @Test
    void set_offline_publishes_stream_offline() {
        actions.setLive(user, true);
        actions.setLive(user, false);

        assertThat(streamState.isLive(user)).isFalse();
        assertThat(published()).filteredOn(StreamOfflineEvent.class::isInstance).hasSize(1);
        assertThat(registry.data(user.getId()).live()).isFalse();
    }

    @Test
    void play_sets_presence_and_triggers_detection() {
        registry.update(user.getId(), d -> d.withPresence(Map.of(GetterConfig.Provider.XBOX,
                new SimulatedPresence("1", "Halo"))));
        user.setSteamId("76561190000000001");

        actions.play(user, GetterConfig.Provider.STEAM, "620", "Portal 2");

        assertThat(registry.data(user.getId()).presence())
                .containsExactly(Map.entry(GetterConfig.Provider.STEAM, new SimulatedPresence("620", "Portal 2")));
        ArgumentCaptor<GetterConfig> saved = ArgumentCaptor.forClass(GetterConfig.class);
        verify(getterConfigs).save(saved.capture());
        assertThat(saved.getValue().getProvider()).isEqualTo(GetterConfig.Provider.STEAM);
        assertThat(saved.getValue().getUser()).isEqualTo(user);
        assertThat(saved.getValue().isEnabled()).isTrue();
        verify(scheduler).triggerManualCheck(user);
    }

    @Test
    void play_keeps_an_existing_getter_config() {
        GetterConfig existing = new GetterConfig();
        existing.setProvider(GetterConfig.Provider.XBOX);
        existing.setEnabled(true);
        when(getterConfigs.findByUserAndProvider(user, GetterConfig.Provider.XBOX)).thenReturn(Optional.of(existing));

        actions.play(user, GetterConfig.Provider.XBOX, "123", "Halo");

        verify(getterConfigs, never()).save(any());
        verify(scheduler).triggerManualCheck(user);
    }

    @Test
    void play_on_steam_without_a_steam_id_fails_clearly() {
        assertThatIllegalStateException()
                .isThrownBy(() -> actions.play(user, GetterConfig.Provider.STEAM, "620", "Portal 2"))
                .withMessageContaining("Steam id");
        verify(scheduler, never()).triggerManualCheck(any());
    }

    @Test
    void stop_playing_clears_presence_and_triggers_detection() {
        registry.update(user.getId(), d -> d.withPresence(Map.of(GetterConfig.Provider.XBOX,
                new SimulatedPresence("1", "Halo"))));

        actions.stopPlaying(user);

        assertThat(registry.data(user.getId()).presence()).isEmpty();
        verify(scheduler).triggerManualCheck(user);
    }

    @Test
    void manual_category_change_reaches_the_twitchat_dispatcher() {
        actions.manualCategoryChange(user, "33214", "Fortnite");

        verify(twitchat).onCategoryChangedManually(user, "33214", "Fortnite", "Just Chatting");
        assertThat(registry.data(user.getId()).currentCategoryId()).isEqualTo("33214");
        assertThat(registry.data(user.getId()).currentCategoryName()).isEqualTo("Fortnite");
    }

    @Test
    void raid_out_publishes_an_outgoing_raid_with_the_current_category() {
        actions.manualCategoryChange(user, "33214", "Fortnite");

        actions.raidOut(user, "bob");

        assertThat(published()).filteredOn(ChannelRaidedOutEvent.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((ChannelRaidedOutEvent) e).getCurrentCategoryId()).isEqualTo("33214"));
    }

    @Test
    void set_bot_goes_through_the_bot_toggle() {
        actions.setBot(user, true);

        verify(botToggle).setBotEnabled(user, true);
    }

    @Test
    void search_games_uses_the_twitch_category_search() {
        List<TwitchCategory> found = List.of(new TwitchCategory("620", "Portal 2", "url"));
        when(twitch.searchCategories(user, "portal")).thenReturn(found);

        assertThat(actions.searchGames(user, "portal")).isEqualTo(found);
    }
}
