package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.site.SiteRoles;
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
import fr.enimaloc.catapult.service.stream.SessionTimelineService;
import fr.enimaloc.catapult.service.stream.StreamSessionService;
import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole;
import fr.enimaloc.catapult.service.emergency.MentionSender;
import fr.enimaloc.catapult.service.emergency.SystemMention;
import fr.enimaloc.catapult.service.emergency.SystemMentionEvent;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
    SystemTwitchAccountService systemAccount = mock(SystemTwitchAccountService.class);
    StreamStateService streamState = new StreamStateService(mock(ChannelEventPublisher.class), twitchat);
    StreamSessionService sessions = mock(StreamSessionService.class);
    SessionTimelineService timeline = mock(SessionTimelineService.class);
    SimulationJournal journal = new SimulationJournal();

    SimulationRegistry registry;
    TwitchEventSubService eventSub;
    StaticListableBeanFactory beans;
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
        when(systemAccount.getSystemTwitchId()).thenReturn("sys-1");
        when(systemAccount.getSystemTwitchUsername()).thenReturn("Catapult_Bot");

        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        registry = new SimulationRegistry(properties, accounts, tokens, mock(MinecraftFriendLinkRepository.class),
                mock(MinecraftServiceAccountRepository.class), new MockEnvironment());
        beans = new StaticListableBeanFactory();
        beans.addBean("simulationGate", new SimulationGate(registry));
        eventSub = new TwitchEventSubService(tokens, accounts, mock(TwitchTokenService.class), streamState,
                publisher, RestClient.create(), new ObjectMapper(), twitchat, selfChanges, DebugRecording.OFF,
                beans.getBeanProvider(SimulationGate.class), SiteRoles.active(),
                sessions, mock(fr.enimaloc.catapult.service.stream.StreamCatchUp.class),
                beans.getBeanProvider(fr.enimaloc.catapult.service.gueststar.GuestStarEventSub.class));
        beans.addBean("twitchEventSubService", eventSub);
        actions = new SimulationActions(registry, journal, beans.getBeanProvider(TwitchEventSubService.class),
                scheduler, getterConfigs, botToggle, twitch, publisher, systemAccount, accounts, sessions, timeline,
                "owner-1");
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

    private ChannelRaidedOutEvent raid() {
        return published().stream().filter(ChannelRaidedOutEvent.class::isInstance)
                .map(ChannelRaidedOutEvent.class::cast).findFirst().orElseThrow();
    }

    @Test
    void raidOutTargetsTheMockAccountByLogin() {
        UserAccount bob = new UserAccount();
        bob.setId(UUID.randomUUID());
        bob.setTwitchId("mock-bob");
        bob.setTwitchUsername("Bob");
        when(accounts.findFirstByTwitchUsernameIgnoreCase("bob")).thenReturn(Optional.of(bob));

        actions.raidOut(user, "bob");

        assertThat(raid().getToBroadcasterId()).isEqualTo("mock-bob");
        assertThat(raid().getFromBroadcasterName()).isEqualTo("alice");
    }

    @Test
    void raidOutToAnUnknownLoginKeepsAnEmptyId() {
        when(accounts.findFirstByTwitchUsernameIgnoreCase(any())).thenReturn(Optional.empty());

        actions.raidOut(user, "nobody");

        assertThat(raid().getToBroadcasterId()).isEmpty();
    }

    @Test
    void setLiveRecordsASimulatedStreamOfItsOwn() {
        actions.setLive(user, true);

        verify(sessions).recordStart(eq(user), startsWith("sim-"), any(Instant.class));
    }

    @Test
    void addPastSegmentRefusedWhenNotLive() {
        when(sessions.find(user)).thenReturn(Optional.empty());

        assertThatIllegalStateException().isThrownBy(() ->
                actions.addPastSegment(user, "1", "Factorio", "Factorio", Duration.ofMinutes(150)));
        verify(timeline, never()).addPast(any(), any(), any(), any(), any());
    }

    @Test
    void addPastSegmentGoesToTheTimelineDuringALive() {
        StreamSession session = new StreamSession();
        session.setStreamId("sim-1");
        session.setStartedAt(Instant.now());
        when(sessions.find(user)).thenReturn(Optional.of(session));

        actions.addPastSegment(user, "1", "Factorio", null, Duration.ofMinutes(150));

        verify(timeline).addPast(user, "1", "Factorio", null, Duration.ofMinutes(150));
    }

    @Test
    void sessionTimelineReadsTheCurrentSession() {
        StreamCategorySegment segment = new StreamCategorySegment();
        when(timeline.currentSession(user)).thenReturn(List.of(segment));

        assertThat(actions.sessionTimeline(user)).containsExactly(segment);
    }

    private List<SystemMentionEvent> mentions() {
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.atLeast(0)).publishEvent(events.capture());
        return events.getAllValues().stream().filter(SystemMentionEvent.class::isInstance)
                .map(SystemMentionEvent.class::cast).toList();
    }

    @Test
    void chat_message_mentioning_the_system_account_publishes_a_mention_event() {
        actions.chatMessage(user, "viewer1", SenderRole.MODERATOR, false, "@catapult_bot emergency xbox");

        assertThat(mentions()).singleElement().satisfies(e -> {
            assertThat(e.channel()).isEqualTo(user);
            assertThat(e.sender()).isEqualTo(new MentionSender("sim:viewer1", "viewer1", SenderRole.MODERATOR));
            assertThat(e.mention()).isEqualTo(new SystemMention("emergency", List.of("xbox")));
        });
        assertThat(journal.recent(user.getId())).extracting(SimulationJournal.Entry::key)
                .containsExactly(SimulatedCalls.TWITCH_EVENTSUB_CHAT_MESSAGE.id());
    }

    @Test
    void chat_message_from_the_owner_carries_the_owner_id() {
        actions.chatMessage(user, "boss", SenderRole.BROADCASTER, true, "@Catapult_Bot off");

        assertThat(mentions()).singleElement().satisfies(e ->
                assertThat(e.sender()).isEqualTo(new MentionSender("owner-1", "boss", SenderRole.BROADCASTER)));
    }

    @Test
    void chat_message_without_a_leading_mention_is_only_journaled() {
        actions.chatMessage(user, "viewer1", SenderRole.VIEWERS, false, "hello @catapult_bot off");
        actions.chatMessage(user, "viewer1", SenderRole.VIEWERS, false, "!song");

        assertThat(mentions()).isEmpty();
        assertThat(journal.recent(user.getId())).hasSize(2);
    }

    @Test
    void chat_message_mentioning_someone_else_is_no_command() {
        actions.chatMessage(user, "viewer1", SenderRole.VIEWERS, false, "@someone_else off");
        actions.chatMessage(user, "viewer1", SenderRole.VIEWERS, false, "@catapult_botx off");

        assertThat(mentions()).isEmpty();
    }

    @Test
    void chat_message_mention_alone_is_not_a_command() {
        actions.chatMessage(user, "viewer1", SenderRole.VIEWERS, false, "@catapult_bot");

        assertThat(mentions()).isEmpty();
    }

    @Test
    void chat_message_without_a_system_account_is_refused() {
        when(systemAccount.getSystemTwitchUsername()).thenReturn(null);

        assertThatIllegalStateException()
                .isThrownBy(() -> actions.chatMessage(user, "viewer1", SenderRole.VIEWERS, false, "@bot off"))
                .withMessageContaining("No system account is linked");
        assertThat(mentions()).isEmpty();
    }

    @Test
    void chat_message_as_the_owner_needs_the_owner_id() {
        SimulationActions noOwner = new SimulationActions(registry, journal,
                new StaticListableBeanFactory().getBeanProvider(TwitchEventSubService.class), scheduler,
                getterConfigs, botToggle, twitch, publisher, systemAccount, accounts, sessions, timeline, "");

        assertThatIllegalStateException()
                .isThrownBy(() -> noOwner.chatMessage(user, "boss", SenderRole.VIEWERS, true, "@catapult_bot off"))
                .withMessageContaining("app.owner-id");
    }

    @Test
    void chat_message_is_refused_while_its_event_is_real() {
        registry.set(realUser.getId(), SimulatedCalls.TWITCH_EVENTSUB_CHAT_MESSAGE, CallSetting.real());

        assertThatIllegalStateException()
                .isThrownBy(() -> actions.chatMessage(realUser, "v", SenderRole.VIEWERS, false, "@catapult_bot off"))
                .withMessageContaining("is Real: the real event arrives");
        assertThat(mentions()).isEmpty();
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

    // ---------------------------------------------------------------- Guest Star

    /** A roster fed by the real EventSub handling, as when the channel's Guest Star subscriptions run. */
    private fr.enimaloc.catapult.service.gueststar.GuestStarRoster followGuests() {
        var roster = new fr.enimaloc.catapult.service.gueststar.GuestStarRoster(mock(ApplicationEventPublisher.class));
        beans.addBean("guestStarEventSub", new fr.enimaloc.catapult.service.gueststar.GuestStarEventSub(roster,
                mock(fr.enimaloc.catapult.service.gueststar.GuestStarConsent.class),
                mock(fr.enimaloc.catapult.service.title.StreamTitleService.class),
                mock(fr.enimaloc.catapult.service.gueststar.GuestStarSessionClient.class)));
        return roster;
    }

    @Test
    void guestStarsArriveThroughTheRealEventSubHandling() {
        var roster = followGuests();

        actions.setGuestStars(user, List.of("Carol", "Dave"));

        assertThat(roster.names(user.getId())).contains("Carol, Dave");
        assertThat(registry.data(user.getId()).guestStars())
                .extracting(SimulatedData.SimulatedGuest::slotId, SimulatedData.SimulatedGuest::displayName)
                .containsExactly(tuple("1", "Carol"), tuple("2", "Dave"));
        assertThat(journal.recent(null)).extracting(SimulationJournal.Entry::key)
                .contains(SimulatedCalls.TWITCH_EVENTSUB_GUEST_STAR_SESSION.id(),
                        SimulatedCalls.TWITCH_EVENTSUB_GUEST_STAR_GUEST.id());
    }

    @Test
    void aGuestLeavingFreesTheirSlotForTheNextOne() {
        var roster = followGuests();
        actions.setGuestStars(user, List.of("Carol", "Dave"));

        actions.setGuestStars(user, List.of("Dave", "Erin"));

        assertThat(roster.names(user.getId())).contains("Erin, Dave");
        assertThat(registry.data(user.getId()).guestStars())
                .extracting(SimulatedData.SimulatedGuest::slotId, SimulatedData.SimulatedGuest::displayName)
                .containsExactly(tuple("1", "Erin"), tuple("2", "Dave"));
    }

    @Test
    void noGuestLeftEndsTheSession() {
        var roster = followGuests();
        actions.setGuestStars(user, List.of("Carol"));

        actions.setGuestStars(user, List.of());

        assertThat(roster.names(user.getId())).isEmpty();
        assertThat(registry.data(user.getId()).guestStars()).isEmpty();
    }

    @Test
    void guestStarsAreRefusedWhileTheirEventsAreReal() {
        registry.set(realUser.getId(), SimulatedCalls.TWITCH_EVENTSUB_GUEST_STAR_GUEST, CallSetting.real());

        assertThatIllegalStateException().isThrownBy(() -> actions.setGuestStars(realUser, List.of("Carol")));
        assertThat(registry.data(realUser.getId()).guestStars()).isEmpty();
    }

    @Test
    void theGuestStarConsentIsKeptInTheSimulatedData() {
        actions.setGuestStarConsent(user, true);
        assertThat(registry.data(user.getId()).guestStarConsent()).isTrue();

        actions.setGuestStarConsent(user, false);
        assertThat(registry.data(user.getId()).guestStarConsent()).isFalse();
    }
}
