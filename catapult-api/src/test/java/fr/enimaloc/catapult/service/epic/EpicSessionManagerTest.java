package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.service.epic.EpicTokenService.AccessToken;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient.Summary;
import fr.enimaloc.catapult.service.epic.api.EpicPresenceClient;
import fr.enimaloc.catapult.service.epic.connect.EpicConnectException;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.SiteRoleChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EpicSessionManagerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T07:00:00Z");
    private static final String STREAMER = "51752b8577e341a6a96f95f6a95f38d5";
    private static final String FALL_GUYS_ID = "d42d7101bb7a41c09a810ee418b7d3d0";
    private static final EpicGame FALL_GUYS = new EpicGame(FALL_GUYS_ID, "Fall Guys", true);

    /** Spike 07:00:32: the streamer is in the launcher only. */
    private static final String LAUNCHER_ONLY = "{\"accountId\":\"" + STREAMER + "\",\"status\":\"online\","
            + "\"perNs\":[{\"ns\":\"_\",\"status\":\"online\",\"party\":{\"joinable\":false},"
            + "\"conns\":[{\"id\":\"c1\",\"props\":{}}]}]}";
    /** Spike 07:01:07: Fall Guys is running. */
    private static final String IN_FALL_GUYS = "{\"accountId\":\"" + STREAMER + "\",\"status\":\"online\","
            + "\"perNs\":[{\"productId\":\"" + FALL_GUYS_ID + "\",\"appId\":\"fghi45677JW4O6a40cxsOpscCLcr3Z3j\","
            + "\"ns\":\"8bedfebaf56f406ebab78986ada3f9b3\",\"status\":\"online\",\"activity\":{},"
            + "\"props\":{\"EOS_ProductName\":\"Fall Guys\",\"EOS_Platform\":\"WIN\","
            + "\"EOS_IntegratedPlatform\":\"EGS\"},\"conns\":[{\"id\":\"c2\",\"props\":{}}]},"
            + "{\"ns\":\"_\",\"status\":\"online\",\"conns\":[{\"id\":\"c1\",\"props\":{}}]}]}";
    /** Spike 07:01:22: Fall Guys was quit. */
    private static final String QUIT_FALL_GUYS = LAUNCHER_ONLY;

    private final EpicServiceAccountRepository repository = mock(EpicServiceAccountRepository.class);
    private final EpicTokenService tokens = mock(EpicTokenService.class);
    private final EpicFriendsClient friends = mock(EpicFriendsClient.class);
    private final EpicPresenceClient presence = mock(EpicPresenceClient.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    /** Clients handed out first, in order; then fresh ones. */
    private final Deque<FakeEpicConnectClient> prepared = new ArrayDeque<>();
    private final List<FakeEpicConnectClient> clients = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    private EpicServiceAccount bot1;
    private EpicServiceAccount bot2;
    private final Summary summary = new Summary(Set.of(STREAMER), Set.of(), Set.of("other"));

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bot1 = account("Catapult-1", "27871f9dd56446c8a1f1d3db1aaa8a7e");
        bot2 = account("Catapult-2", "bot2bot2bot2bot2bot2bot2bot2bot2");
        when(siteRole.isActive()).thenReturn(true);
        when(repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1, bot2));
        when(repository.findById(bot1.getId())).thenReturn(Optional.of(bot1));
        when(repository.findById(bot2.getId())).thenReturn(Optional.of(bot2));
        when(tokens.token(bot1.getId())).thenReturn(token("access-1"));
        when(tokens.token(bot2.getId())).thenReturn(token("access-2"));
        when(friends.summary(anyString(), anyString())).thenReturn(summary);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static AccessToken token(String value) {
        return new AccessToken(value, NOW.plus(Duration.ofHours(2)));
    }

    private static EpicServiceAccount account(String name, String epicAccountId) {
        EpicServiceAccount account = new EpicServiceAccount();
        account.setId(UUID.randomUUID());
        account.setDisplayName(name);
        account.setEpicAccountId(epicAccountId);
        account.setRefreshTokenEnc("unused");
        account.setRefreshExpiresAt(NOW.plus(Duration.ofDays(300)));
        account.setAuthState(AuthState.READY);
        account.setEnabled(true);
        return account;
    }

    private EpicSessionManager manager(boolean sessionsEnabled) {
        return new EpicSessionManager(repository, tokens, friends, presence, siteRole, events, registry,
                sessionsEnabled,
                () -> {
                    FakeEpicConnectClient client = prepared.isEmpty() ? new FakeEpicConnectClient() : prepared.poll();
                    clients.add(client);
                    return client;
                },
                scheduler, Runnable::run, Runnable::run, clock, Duration.ofMillis(1));
    }

    private EpicSessionManager started() {
        EpicSessionManager manager = manager(true);
        manager.start();
        return manager;
    }

    /** The clients of one bot, oldest first. */
    private List<FakeEpicConnectClient> clientsOf(EpicServiceAccount bot) {
        return clients.stream().filter(c -> bot.getEpicAccountId().equals(c.botAccountId)).toList();
    }

    private FakeEpicConnectClient lastClientOf(EpicServiceAccount bot) {
        return clientsOf(bot).getLast();
    }

    private List<EpicFriendEventReceived> publishedFor(EpicServiceAccount bot) {
        ArgumentCaptor<EpicFriendEventReceived> captor = ArgumentCaptor.forClass(EpicFriendEventReceived.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream().filter(e -> e.serviceAccountId().equals(bot.getId())).toList();
    }

    private double counter(String name) {
        return registry.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static void eventually(Runnable assertion) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.onSpinWait();
            }
        }
    }

    @Test
    void sessionsDoNotOpen_whenDisabled_orStandby() {
        EpicSessionManager disabled = manager(false);
        disabled.start();
        disabled.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));
        disabled.restart(bot1.getId());
        disabled.resyncAll();

        assertThat(clients).isEmpty();
        assertThat(disabled.sessionState(bot1.getId())).isEqualTo(EpicSessionManager.DISABLED);
        verifyNoInteractions(repository, tokens);

        when(siteRole.isActive()).thenReturn(false);
        EpicSessionManager standby = started();

        assertThat(clients).isEmpty();
        assertThat(standby.sessionState(bot1.getId())).isEqualTo(EpicSessionManager.DISABLED);
        assertThat(standby.anyOpenSessionToken()).isEmpty();
        verifyNoInteractions(tokens);
    }

    @Test
    void eachReadyEnabledBot_getsOneSession() {
        EpicServiceAccount refused = account("Catapult-3", "bot3");
        refused.setAuthState(AuthState.REAUTH_REQUIRED);
        EpicServiceAccount disabledBot = account("Catapult-4", "bot4");
        disabledBot.setEnabled(false);
        when(repository.findById(refused.getId())).thenReturn(Optional.of(refused));
        when(repository.findById(disabledBot.getId())).thenReturn(Optional.of(disabledBot));

        EpicSessionManager manager = started();
        manager.restart(refused.getId());
        manager.restart(disabledBot.getId());

        assertThat(clients).hasSize(2);
        assertThat(clients).extracting(c -> c.accessToken).containsExactly("access-1", "access-2");
        assertThat(clients).extracting(c -> c.botAccountId)
                .containsExactly(bot1.getEpicAccountId(), bot2.getEpicAccountId());
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("CONNECTING");
        assertThat(manager.sessionState(refused.getId())).isEqualTo(EpicSessionManager.DISABLED);
        assertThat(manager.sessionState(disabledBot.getId())).isEqualTo(EpicSessionManager.DISABLED);

        clients.get(0).connected("conn-1");
        clients.get(1).connected("conn-2");
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
        assertThat(manager.sessionState(bot2.getId())).isEqualTo("READY");
        assertThat(manager.readySessionCount()).isEqualTo(2);

        manager.restart(bot1.getId());
        assertThat(clients).hasSize(3);
        assertThat(clients.getFirst().closed).isTrue();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("CONNECTING");

        manager.stop(bot1.getId());
        assertThat(clients.get(2).closed).isTrue();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(EpicSessionManager.DISABLED);
        assertThat(manager.readySessionCount()).isEqualTo(1);
    }

    @Test
    void connected_registersThePresence_thenResyncsFriends() {
        EpicSessionManager manager = started();
        assertThat(manager.anyOpenSessionToken()).isEmpty();

        lastClientOf(bot1).connected("860311ff");

        InOrder order = inOrder(presence, friends, events);
        order.verify(presence).register("access-1", bot1.getEpicAccountId(), "860311ff");
        order.verify(friends).summary("access-1", bot1.getEpicAccountId());
        order.verify(events).publishEvent(new EpicFriendEventReceived(bot1.getId(), summary));
        assertThat(manager.anyOpenSessionToken()).contains("access-1");
        verify(friends, never()).summary(eq("access-2"), anyString());
    }

    @Test
    void spikeReplay_setsFallGuys_thenClearsIt() {
        EpicSessionManager manager = started();
        FakeEpicConnectClient client = lastClientOf(bot1);
        client.connected("860311ff");

        client.push(EpicSessionManager.PRESENCE_UPDATE, LAUNCHER_ONLY);
        assertThat(manager.currentGame(STREAMER)).isEmpty();

        client.push(EpicSessionManager.PRESENCE_UPDATE, IN_FALL_GUYS);
        assertThat(manager.currentGame(STREAMER)).contains(FALL_GUYS);
        assertThat(manager.currentGame("someone-else")).isEmpty();

        client.push(EpicSessionManager.PRESENCE_UPDATE, QUIT_FALL_GUYS);
        assertThat(manager.currentGame(STREAMER)).isEmpty();
    }

    @Test
    void sessionClosing_forgetsTheGamesItReported() {
        EpicSessionManager manager = started();
        FakeEpicConnectClient first = lastClientOf(bot1);
        first.connected("conn-1");
        lastClientOf(bot2).connected("conn-2");
        first.push(EpicSessionManager.PRESENCE_UPDATE, IN_FALL_GUYS);
        lastClientOf(bot2).push(EpicSessionManager.PRESENCE_UPDATE,
                IN_FALL_GUYS.replace(STREAMER, "another-streamer"));

        first.drop();

        assertThat(manager.currentGame(STREAMER)).isEmpty();
        assertThat(manager.currentGame("another-streamer")).contains(FALL_GUYS);
        assertThat(counter(EpicSessionManager.DROPPED_COUNTER)).isEqualTo(1.0);
        // The bot reconnects with a fresh client; nothing the dropped one says afterwards counts
        eventually(() -> assertThat(clientsOf(bot1)).hasSize(2));
        first.push(EpicSessionManager.PRESENCE_UPDATE, IN_FALL_GUYS);
        assertThat(manager.currentGame(STREAMER)).isEmpty();

        manager.stop(bot2.getId());
        assertThat(manager.currentGame("another-streamer")).isEmpty();
    }

    @Test
    void friendsMessage_triggersAResync() {
        started();
        FakeEpicConnectClient client = lastClientOf(bot1);
        client.connected("conn-1");

        client.push("friends.v1.FRIENDSHIP_REQUEST", "{\"from\":\"" + STREAMER + "\"}");

        verify(friends, times(2)).summary("access-1", bot1.getEpicAccountId());
        assertThat(publishedFor(bot1)).hasSize(2)
                .allMatch(e -> e.summary().equals(summary));
    }

    @Test
    void periodicResync_listsTheFriendsOfRegisteredSessionsOnly() {
        EpicSessionManager manager = started();
        lastClientOf(bot1).connected("conn-1");

        manager.resyncAll();

        verify(friends, times(2)).summary("access-1", bot1.getEpicAccountId());
        verify(friends, never()).summary(eq("access-2"), anyString());
    }

    @Test
    void tokenNearExpiry_reconnectsWithAFreshToken() {
        when(tokens.token(bot1.getId()))
                .thenReturn(new AccessToken("old", NOW.plus(EpicSessionManager.RENEW_BEFORE_EXPIRY)))
                .thenReturn(token("fresh"));
        EpicSessionManager manager = started();
        FakeEpicConnectClient old = clientsOf(bot1).getFirst();

        eventually(() -> assertThat(clientsOf(bot1)).hasSize(2));
        FakeEpicConnectClient renewed = lastClientOf(bot1);
        assertThat(old.closed).isTrue();
        assertThat(renewed.accessToken).isEqualTo("fresh");
        assertThat(renewed.closed).isFalse();

        renewed.connected("conn-2");
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
        assertThat(manager.anyOpenSessionToken()).contains("fresh");
        // A planned renewal is not a drop
        assertThat(counter(EpicSessionManager.DROPPED_COUNTER)).isZero();
    }

    @Test
    void refusedRefresh_stopsTheSession() {
        when(tokens.token(bot1.getId()))
                .thenThrow(new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED));

        EpicSessionManager manager = started();

        assertThat(manager.sessionState(bot1.getId())).isEqualTo(EpicSessionManager.DISABLED);
        assertThat(clientsOf(bot1)).isEmpty();
        assertThat(manager.sessionState(bot2.getId())).isEqualTo("CONNECTING");
        assertThat(counter(EpicSessionManager.ADMIN_ACTION_COUNTER)).isEqualTo(1.0);
        verify(tokens, times(1)).token(bot1.getId());
    }

    @Test
    void rejectedAccessToken_isEvicted_andRetriedAtOnceWithAFreshOne() {
        when(tokens.token(bot1.getId())).thenReturn(token("rejected")).thenReturn(token("fresh"));
        doThrow(new EpicAuthException(EpicAuthException.Reason.ACCESS_REJECTED))
                .when(presence).register(eq("rejected"), anyString(), anyString());
        EpicSessionManager manager = started();

        clientsOf(bot1).getFirst().connected("conn-1");

        verify(tokens).evict(bot1.getId());
        assertThat(clientsOf(bot1)).hasSize(2);
        assertThat(clientsOf(bot1).getFirst().closed).isTrue();
        FakeEpicConnectClient retried = lastClientOf(bot1);
        assertThat(retried.accessToken).isEqualTo("fresh");
        retried.connected("conn-2");
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
        assertThat(counter(EpicSessionManager.ADMIN_ACTION_COUNTER)).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void rejectedAccessTokenTwice_waitsForTheBackoff() {
        when(tokens.token(bot1.getId())).thenReturn(token("rejected"));
        doThrow(new EpicAuthException(EpicAuthException.Reason.ACCESS_REJECTED))
                .when(presence).register(eq("rejected"), anyString(), anyString());
        EpicSessionManager manager = started();

        clientsOf(bot1).getFirst().connected("conn-1");
        lastClientOf(bot1).connected("conn-2");

        verify(tokens, times(2)).evict(bot1.getId());
        assertThat(manager.sessionState(bot1.getId())).isIn("BACKOFF", "CONNECTING");
        eventually(() -> assertThat(clientsOf(bot1)).hasSize(3));
    }

    @Test
    void failedConnection_isRetried() {
        FakeEpicConnectClient failing = new FakeEpicConnectClient();
        failing.connectFailure = new EpicConnectException("Epic Connect connection failed");
        prepared.add(failing);
        when(repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1));

        EpicSessionManager manager = started();

        eventually(() -> assertThat(clientsOf(bot1)).hasSize(2));
        eventually(() -> assertThat(manager.sessionState(bot1.getId())).isEqualTo("CONNECTING"));
    }

    @Test
    void failedFriendsListing_keepsTheSessionOpen() {
        when(friends.summary(anyString(), anyString())).thenThrow(new RestClientException("HTTP 503"));
        EpicSessionManager manager = started();

        lastClientOf(bot1).connected("conn-1");

        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
        assertThat(lastClientOf(bot1).closed).isFalse();
        verifyNoInteractions(events);
    }

    @Test
    void standby_closesAllSessions() {
        EpicSessionManager manager = started();
        lastClientOf(bot1).connected("conn-1");
        lastClientOf(bot1).push(EpicSessionManager.PRESENCE_UPDATE, IN_FALL_GUYS);

        when(siteRole.isActive()).thenReturn(false);
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));

        assertThat(clients).allMatch(c -> c.closed);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(EpicSessionManager.DISABLED);
        assertThat(manager.sessionState(bot2.getId())).isEqualTo(EpicSessionManager.DISABLED);
        assertThat(manager.currentGame(STREAMER)).isEmpty();
        assertThat(manager.anyOpenSessionToken()).isEmpty();

        manager.resyncAll();
        verify(friends, times(1)).summary(anyString(), anyString());

        when(siteRole.isActive()).thenReturn(true);
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));
        assertThat(clients).hasSize(4);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("CONNECTING");
    }
}
