package fr.enimaloc.catapult.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.stream.RaidRecapListener;
import fr.enimaloc.catapult.service.stream.SessionRecap;
import fr.enimaloc.catapult.service.stream.SessionTimelineListener;
import fr.enimaloc.catapult.service.stream.SessionTimelineService;
import fr.enimaloc.catapult.service.stream.StreamCatchUp;
import fr.enimaloc.catapult.service.stream.StreamSessionService;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.SiteRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A raid between two mock members, end to end on the real path: the panel's actions inject the EventSub
 * notifications, the real handling records the session and publishes the events, the real timeline and raid listeners
 * build the recap, and the raided member's Twitchat dispatcher receives it. The database is the shared H2 context of
 * the timeline tests; the services around the simulation are wired by hand, as the panel's own context would.
 */
@SpringBootTest
@ActiveProfiles("h2-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_timeline_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class SimulationRaidRecapTest {

    @TempDir
    Path dir;

    @Autowired UserAccountRepository accounts;
    @Autowired OAuthTokenRepository tokens;
    @Autowired StreamSessionService sessions;
    @Autowired SessionTimelineService timeline;

    TwitchatEventDispatcher twitchat = mock(TwitchatEventDispatcher.class);
    SimulationActions actions;
    UserAccount raider;
    UserAccount raided;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        raider = member("mock-a" + suffix, "RaiderA" + suffix);
        raided = member("mock-b" + suffix, "RaidedB" + suffix);

        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        SimulationRegistry registry = new SimulationRegistry(properties, accounts, tokens,
                mock(MinecraftFriendLinkRepository.class), mock(MinecraftServiceAccountRepository.class),
                new MockEnvironment());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("simulationGate", new SimulationGate(registry));

        SiteRole active = SiteRoles.active();
        GameStateService gameState = mock(GameStateService.class);
        CatapultCategoryChangeStateService selfChanges = mock(CatapultCategoryChangeStateService.class);
        when(selfChanges.consumeIfMatches(any(), any())).thenReturn(java.util.Optional.empty());
        StreamStateService streamState = new StreamStateService(mock(ChannelEventPublisher.class), twitchat);
        RaidRecapListener raids = new RaidRecapListener(accounts, sessions, timeline, twitchat, active);
        SessionTimelineListener[] timelineListener = new SessionTimelineListener[1];
        // What the application context does: each event reaches the listeners of its type
        ApplicationEventPublisher publisher = event -> {
            switch (event) {
                case StreamOnlineEvent online -> timelineListener[0].onStreamOnline(online);
                case StreamOfflineEvent offline -> timelineListener[0].onStreamOffline(offline);
                case ChannelRaidedOutEvent raid -> raids.onRaid(raid);
                default -> { }
            }
        };
        TwitchEventSubService eventSub = new TwitchEventSubService(tokens, accounts, mock(TwitchTokenService.class),
                streamState, publisher, RestClient.create(), new ObjectMapper(), twitchat, selfChanges,
                DebugRecording.OFF, beans.getBeanProvider(SimulationGate.class), active, sessions,
                mock(StreamCatchUp.class));
        beans.addBean("twitchEventSubService", eventSub);
        timelineListener[0] = new SessionTimelineListener(timeline, eventSub, gameState, active);
        // As the connection does: the channel's category is known before it goes live, so no Helix read runs aside
        eventSub.seedChannelStateIfAbsent(raider);
        actions = new SimulationActions(registry, new SimulationJournal(),
                beans.getBeanProvider(TwitchEventSubService.class), mock(SchedulerService.class),
                mock(GetterConfigRepository.class), mock(BotToggleService.class), mock(TwitchService.class),
                publisher, mock(SystemTwitchAccountService.class), accounts, sessions, timeline, "owner-1");
    }

    private UserAccount member(String twitchId, String username) {
        UserAccount account = new UserAccount();
        account.setTwitchId(twitchId);
        account.setTwitchUsername(username);
        return accounts.save(account);
    }

    @Test
    void mockRaidBetweenTwoMembersSendsTheRecap() {
        actions.setLive(raider, true);
        // Each one goes before the oldest period: added from the last to the first
        actions.addPastSegment(raider, "66082", "Games+Demos", "MOLTYN", Duration.ofMinutes(105));
        actions.addPastSegment(raider, "509658", "Just Chatting", null, Duration.ofMinutes(60));
        actions.addPastSegment(raider, "263490", "Factorio", "Factorio", Duration.ofMinutes(150));

        actions.raidOut(raider, raided.getTwitchUsername().toLowerCase());

        ArgumentCaptor<UserAccount> target = ArgumentCaptor.forClass(UserAccount.class);
        ArgumentCaptor<SessionRecap> recap = ArgumentCaptor.forClass(SessionRecap.class);
        verify(twitchat).onRaidedByMember(target.capture(), eq(raider.getTwitchUsername().toLowerCase()),
                recap.capture());
        assertThat(target.getValue().getId()).isEqualTo(raided.getId());
        assertThat(recap.getValue().gamesText()).isEqualTo("""
                - Factorio (Factorio) (2h30)
                - Just Chatting (1h)
                - Games+Demos (MOLTYN) (1h45)""");
    }

    @Test
    void aSimulatedLiveRecordsItsSessionAndItsEnd() {
        actions.setLive(raider, true);

        assertThat(sessions.find(raider)).hasValueSatisfying(session -> {
            assertThat(session.getStreamId()).startsWith("sim-");
            assertThat(session.isOngoing()).isTrue();
        });
        assertThat(timeline.currentSession(raider)).singleElement()
                .satisfies(segment -> assertThat(segment.getEndedAt()).isNull());

        actions.setLive(raider, false);

        assertThat(sessions.find(raider)).hasValueSatisfying(session -> assertThat(session.isOngoing()).isFalse());
        assertThat(timeline.currentSession(raider)).singleElement()
                .satisfies(segment -> assertThat(segment.getEndedAt()).isNotNull());
    }
}
