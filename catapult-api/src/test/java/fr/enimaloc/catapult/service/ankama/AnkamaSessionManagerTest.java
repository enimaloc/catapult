package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.EndpointProperties;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.User;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaAuthException;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Certificate;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaShieldException;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.SiteRoleChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnkamaSessionManagerTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private final AnkamaServiceAccountRepository repository = mock(AnkamaServiceAccountRepository.class);
    private final AnkamaHaapiClient haapi = mock(AnkamaHaapiClient.class);
    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final SiteRole siteRole = mock(SiteRole.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    /** Clients handed out first, in order; then fresh ones. */
    private final Deque<FakeChatClient> prepared = new ArrayDeque<>();
    private final List<FakeChatClient> clients = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    private AnkamaServiceAccount bot1;
    private AnkamaServiceAccount bot2;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bot1 = account("Bot1", 1001L);
        bot2 = account("Bot2", 1002L);
        when(siteRole.isActive()).thenReturn(true);
        when(haapi.chatToken(anyString(), any(), anyString())).thenReturn("chat-token");
        when(repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1, bot2));
        when(repository.findByAuthState(AuthState.READY)).thenReturn(List.of(bot1, bot2));
        when(repository.findById(bot1.getId())).thenReturn(Optional.of(bot1));
        when(repository.findById(bot2.getId())).thenReturn(Optional.of(bot2));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private AnkamaServiceAccount account(String name, long ankamaAccountId) {
        AnkamaServiceAccount account = new AnkamaServiceAccount();
        account.setId(UUID.randomUUID());
        account.setNickname(name);
        account.setTag("1234");
        account.setAnkamaAccountId(ankamaAccountId);
        account.setDeviceId("device-" + name);
        account.setHm1("hm1-" + name);
        account.setApiKeyEnc(encryption.encrypt("key-" + name));
        account.setRefreshTokenEnc(encryption.encrypt("refresh-" + name));
        account.setLastRefreshAt(NOW);
        account.setAuthState(AuthState.READY);
        account.setEnabled(true);
        return account;
    }

    private AnkamaSessionManager manager(boolean sessionsEnabled) {
        return manager(sessionsEnabled, Runnable::run);
    }

    private AnkamaSessionManager manager(boolean sessionsEnabled, Executor starter) {
        return new AnkamaSessionManager(repository, haapi, encryption, siteRole, events, registry,
                TransactionOperations.withoutTransaction(), sessionsEnabled,
                () -> {
                    FakeChatClient client = prepared.isEmpty() ? new FakeChatClient() : prepared.poll();
                    clients.add(client);
                    return client;
                },
                scheduler, starter, Runnable::run, clock, Duration.ofMillis(1));
    }

    private AnkamaSessionManager started() {
        AnkamaSessionManager manager = manager(true);
        manager.start();
        return manager;
    }

    private static EndpointProperties endpoint(int applicationId) {
        return new EndpointProperties(applicationId, List.of(), Map.of());
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

    private double adminActions() {
        return registry.find(AnkamaSessionManager.ADMIN_ACTION_COUNTER).counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    @Test
    void sessionsDoNotOpen_whenSessionsAreDisabled_orTheSiteIsStandby() {
        AnkamaSessionManager disabled = manager(false);
        disabled.start();
        disabled.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));
        disabled.restart(bot1.getId());
        disabled.refreshDueTokens();

        assertThat(clients).isEmpty();
        assertThat(disabled.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
        verifyNoInteractions(repository, haapi);

        when(siteRole.isActive()).thenReturn(false);
        AnkamaSessionManager standby = started();

        assertThat(clients).isEmpty();
        assertThat(standby.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
        verifyNoInteractions(haapi);
    }

    @Test
    void eachReadyEnabledBot_getsOneSession() {
        AnkamaServiceAccount waitingForCode = account("Bot3", 1003L);
        waitingForCode.setAuthState(AuthState.CODE_REQUIRED);
        AnkamaServiceAccount disabled = account("Bot4", 1004L);
        disabled.setEnabled(false);
        when(repository.findById(waitingForCode.getId())).thenReturn(Optional.of(waitingForCode));
        when(repository.findById(disabled.getId())).thenReturn(Optional.of(disabled));

        AnkamaSessionManager manager = started();
        manager.restart(waitingForCode.getId());
        manager.restart(disabled.getId());

        assertThat(clients).hasSize(2);
        assertThat(clients).extracting(c -> c.token).containsOnly("chat-token");
        assertThat(clients).extracting(c -> c.deviceId).containsExactly("device-Bot1", "device-Bot2");
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
        assertThat(manager.sessionState(bot2.getId())).isEqualTo("READY");
        assertThat(manager.sessionState(waitingForCode.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
        assertThat(manager.sessionState(disabled.getId())).isEqualTo(AnkamaSessionManager.DISABLED);

        manager.restart(bot1.getId());
        assertThat(clients).hasSize(3);
        assertThat(clients.getFirst().closed).isTrue();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");

        manager.stop(bot1.getId());
        assertThat(clients.get(2).closed).isTrue();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
    }

    @Test
    void endpointsEvent_setsTheGame_andLauncherOnlyClearsIt() {
        AnkamaSessionManager manager = started();
        FakeChatClient client = clients.getFirst();

        client.push(new ChatMessages.EndpointsUpdated(42L, List.of(endpoint(3), endpoint(102))));
        assertThat(manager.currentGame(42L)).isEqualTo(OptionalInt.of(3));
        assertThat(manager.currentGame(43L)).isEmpty();

        client.push(new ChatMessages.EndpointsUpdated(42L, List.of(endpoint(102))));
        assertThat(manager.currentGame(42L)).isEmpty();
    }

    @Test
    void offlinePresence_clearsTheGame() {
        AnkamaSessionManager manager = started();
        FakeChatClient client = clients.getFirst();
        client.push(new ChatMessages.EndpointsUpdated(42L, List.of(endpoint(1))));

        client.push(new ChatMessages.PresenceUpdated(42L, ChatMessages.PRESENCE_OFFLINE));
        assertThat(manager.currentGame(42L)).isEmpty();

        client.push(new ChatMessages.PresenceUpdated(42L, ChatMessages.PRESENCE_ONLINE));
        assertThat(manager.currentGame(42L)).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void sessionClosing_forgetsTheGamesItReported() {
        AnkamaSessionManager manager = started();
        clients.get(0).push(new ChatMessages.EndpointsUpdated(42L, List.of(endpoint(3))));
        clients.get(1).push(new ChatMessages.EndpointsUpdated(77L, List.of(endpoint(22))));

        clients.get(0).drop();

        assertThat(manager.currentGame(42L)).isEmpty();
        assertThat(manager.currentGame(77L)).isEqualTo(OptionalInt.of(22));
        // The bot reconnects with a fresh client
        eventually(() -> assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY"));
        assertThat(clients).hasSize(3);

        manager.stop(bot2.getId());
        assertThat(manager.currentGame(77L)).isEmpty();
    }

    @Test
    void afterConnect_friendsAndInvites_areResyncedAsEvents() {
        FakeChatClient client = new FakeChatClient();
        User bot = new User(1001L, "Bot1", "1234");
        ChatMessages.Invite invite = new ChatMessages.Invite(bot, new User(55L, "Streamer", "4242"));
        User friend = new User(66L, "Friend", "0007");
        client.invites = List.of(invite);
        client.friends = List.of(friend);
        prepared.add(client);

        AnkamaSessionManager manager = started();
        client.push(new ChatMessages.FriendDeleted(1001L, 66L));
        client.push(new ChatMessages.EndpointsUpdated(66L, List.of(endpoint(1))));

        ArgumentCaptor<AnkamaFriendEventReceived> captor = ArgumentCaptor.forClass(AnkamaFriendEventReceived.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        List<AnkamaFriendEventReceived> fromBot1 = captor.getAllValues().stream()
                .filter(e -> e.serviceAccountId().equals(bot1.getId())).toList();
        assertThat(fromBot1).extracting(AnkamaFriendEventReceived::event).containsExactly(
                new ChatMessages.InviteCreated(invite),
                new ChatMessages.FriendCreated(1001L, friend),
                new ChatMessages.FriendDeleted(1001L, 66L));
        assertThat(manager.currentGame(66L)).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void request_failsWithoutAnOpenSession_andGoesThroughTheBotsClient() throws Exception {
        AnkamaSessionManager manager = started();

        manager.request(bot1.getId(), ChatMessages.deleteFriend(1001L, 66L).toPayload()).get();
        assertThat(clients.getFirst().requests).extracting(p -> p.id())
                .contains(ChatMessages.DELETE_USER_FRIEND_CMD);

        manager.stop(bot1.getId());
        assertThatThrownBy(() -> manager.request(bot1.getId(),
                ChatMessages.deleteFriend(1001L, 66L).toPayload()).get())
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusedRefresh_setsReauthRequired_andStopsTheSession() {
        bot1.setLastRefreshAt(NOW.minus(Duration.ofHours(25)));
        when(haapi.refresh("key-Bot1", "refresh-Bot1")).thenThrow(new AnkamaAuthException("refused"));
        AnkamaSessionManager manager = started();

        manager.refreshDueTokens();

        assertThat(bot1.getAuthState()).isEqualTo(AuthState.REAUTH_REQUIRED);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
        assertThat(clients.getFirst().closed).isTrue();
        assertThat(manager.sessionState(bot2.getId())).isEqualTo("READY");
        assertThat(adminActions()).isEqualTo(1.0);
    }

    @Test
    void refreshRunsOnlyAfter24Hours() {
        bot1.setLastRefreshAt(NOW.minus(Duration.ofHours(23)));
        bot2.setLastRefreshAt(NOW.minus(Duration.ofHours(25)));
        when(haapi.refresh("key-Bot2", "refresh-Bot2")).thenReturn("new-refresh");
        AnkamaSessionManager manager = started();

        manager.refreshDueTokens();

        verify(haapi, never()).refresh(eq("key-Bot1"), anyString());
        verify(haapi).refresh("key-Bot2", "refresh-Bot2");
        assertThat(encryption.decrypt(bot2.getRefreshTokenEnc())).isEqualTo("new-refresh");
        assertThat(bot2.getLastRefreshAt()).isEqualTo(NOW);
        assertThat(bot2.getAuthState()).isEqualTo(AuthState.READY);
    }

    @Test
    void threeRefusedChatAuthentications_setReauthRequired() {
        when(repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1));
        for (int i = 0; i < 3; i++) {
            prepared.add(FakeChatClient.refusing());
        }
        when(haapi.refresh("key-Bot1", "refresh-Bot1")).thenThrow(new AnkamaAuthException("refused"));
        AnkamaSessionManager manager = started();

        eventually(() -> assertThat(bot1.getAuthState()).isEqualTo(AuthState.REAUTH_REQUIRED));
        eventually(() -> assertThat(manager.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED));
        assertThat(clients).hasSize(3);
        verify(haapi, times(1)).refresh("key-Bot1", "refresh-Bot1");
        assertThat(adminActions()).isEqualTo(1.0);
    }

    @Test
    void refusedChatToken_isRetriedOnceAfterARefresh() {
        when(haapi.chatToken(eq("key-Bot1"), any(), anyString()))
                .thenThrow(new AnkamaAuthException("refused")).thenReturn("chat-token");
        when(haapi.refresh("key-Bot1", "refresh-Bot1")).thenReturn("new-refresh");

        AnkamaSessionManager manager = started();

        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
        assertThat(encryption.decrypt(bot1.getRefreshTokenEnc())).isEqualTo("new-refresh");
        assertThat(bot1.getAuthState()).isEqualTo(AuthState.READY);
    }

    @Test
    void chatToken_carriesTheBotsCertificate() {
        bot1.setCertificateId(7L);
        bot1.setCertificateEnc(encryption.encrypt("encoded-certificate"));

        started();

        verify(haapi).chatToken("key-Bot1", new Certificate(7L, "encoded-certificate"), "hm1-Bot1");
        verify(haapi).chatToken("key-Bot2", null, "hm1-Bot2");
    }

    @Test
    void certificateRefused_setsCodeRequired_sendsACode_andStopsTheSession() {
        when(haapi.chatToken(eq("key-Bot1"), any(), anyString()))
                .thenThrow(new AnkamaShieldException("Ankama requires a valid Shield certificate"));

        AnkamaSessionManager manager = started();

        assertThat(bot1.getAuthState()).isEqualTo(AuthState.CODE_REQUIRED);
        verify(haapi).sendShieldCode("key-Bot1");
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
        assertThat(clients).hasSize(1);
        assertThat(manager.sessionState(bot2.getId())).isEqualTo("READY");
        assertThat(adminActions()).isEqualTo(1.0);
    }

    @Test
    void standby_closesAllSessions() {
        AnkamaSessionManager manager = started();
        clients.getFirst().push(new ChatMessages.EndpointsUpdated(42L, List.of(endpoint(3))));

        when(siteRole.isActive()).thenReturn(false);
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));

        assertThat(clients).allMatch(c -> c.closed);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
        assertThat(manager.sessionState(bot2.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
        assertThat(manager.currentGame(42L)).isEmpty();

        manager.refreshDueTokens();
        verify(haapi, never()).refresh(anyString(), anyString());

        when(siteRole.isActive()).thenReturn(true);
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));
        assertThat(clients).hasSize(4);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
    }

    @Test
    void threeMoreRefusals_afterASuccessfulRefresh_setReauthRequired() {
        when(repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1));
        for (int i = 0; i < 6; i++) {
            prepared.add(FakeChatClient.refusing());
        }
        when(haapi.refresh("key-Bot1", "refresh-Bot1")).thenReturn("new-refresh");
        AnkamaSessionManager manager = started();

        eventually(() -> assertThat(bot1.getAuthState()).isEqualTo(AuthState.REAUTH_REQUIRED));
        eventually(() -> assertThat(manager.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED));
        assertThat(clients).hasSize(6);
        verify(haapi, times(1)).refresh(anyString(), anyString());
        assertThat(encryption.decrypt(bot1.getRefreshTokenEnc())).isEqualTo("new-refresh");
        assertThat(adminActions()).isEqualTo(1.0);
    }

    /** Bot1's first chat token call blocks until released, then fails with {@code failure}. */
    private final class BlockedChatToken {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService starter = Executors.newSingleThreadExecutor();

        BlockedChatToken(RuntimeException failure) {
            when(repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                    .thenReturn(List.of(bot1));
            when(haapi.chatToken(eq("key-Bot1"), any(), anyString())).thenAnswer(invocation -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                throw failure;
            }).thenReturn("chat-token");
        }

        AnkamaSessionManager start() throws InterruptedException {
            AnkamaSessionManager manager = manager(true, starter);
            manager.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            return manager;
        }

        /** Lets the blocked call fail and waits for every queued attempt. */
        void finish() throws InterruptedException {
            release.countDown();
            starter.shutdown();
            assertThat(starter.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void attemptStoppedDuringTheChatTokenCall_neitherRefreshesNorTouchesTheAccount() throws Exception {
        BlockedChatToken refused = new BlockedChatToken(new AnkamaAuthException("refused"));
        AnkamaSessionManager manager = refused.start();

        manager.stop(bot1.getId());
        refused.finish();

        verify(haapi, never()).refresh(anyString(), anyString());
        verify(repository, never()).save(any());
        assertThat(bot1.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(clients).isEmpty();
        assertThat(adminActions()).isZero();
    }

    @Test
    void attemptStoppedDuringTheChatTokenCall_sendsNoShieldCode() throws Exception {
        BlockedChatToken shield = new BlockedChatToken(new AnkamaShieldException("certificate"));
        AnkamaSessionManager manager = shield.start();

        manager.stop(bot1.getId());
        shield.finish();

        verify(haapi, never()).sendShieldCode(anyString());
        verify(repository, never()).save(any());
        assertThat(bot1.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(adminActions()).isZero();
    }

    @Test
    void standbyDuringTheChatTokenCall_refreshesNothing() throws Exception {
        BlockedChatToken refused = new BlockedChatToken(new AnkamaAuthException("refused"));
        AnkamaSessionManager manager = refused.start();

        when(siteRole.isActive()).thenReturn(false);
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));
        refused.finish();

        verify(haapi, never()).refresh(anyString(), anyString());
        assertThat(bot1.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(AnkamaSessionManager.DISABLED);
    }

    @Test
    void restartDuringARefusedChatTokenCall_keepsTheNewSession() throws Exception {
        BlockedChatToken refused = new BlockedChatToken(new AnkamaAuthException("refused"));
        AnkamaSessionManager manager = refused.start();

        manager.restart(bot1.getId());
        refused.finish();

        verify(haapi, never()).refresh(anyString(), anyString());
        verify(repository, never()).save(any());
        assertThat(bot1.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("READY");
        assertThat(clients).hasSize(1);
        assertThat(clients.getFirst().closed).isFalse();
        assertThat(adminActions()).isZero();
    }
}
