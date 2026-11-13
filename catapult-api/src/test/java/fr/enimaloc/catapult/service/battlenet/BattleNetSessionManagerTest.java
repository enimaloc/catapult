package fr.enimaloc.catapult.service.battlenet;

import bgs.protocol.connection.v1.ConnectionServiceProto.ConnectResponse;
import bgs.protocol.connection.v1.ConnectionServiceProto.DisconnectNotification;
import fr.enimaloc.catapult.bgs.client.FriendEvent;
import fr.enimaloc.catapult.bgs.client.PresenceEvent;
import fr.enimaloc.catapult.bgs.client.TicketException;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginCoordinator;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.SiteRoleChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static fr.enimaloc.catapult.bgs.proto.BgsServices.CONNECTION_CONNECT;
import static fr.enimaloc.catapult.bgs.proto.BgsServices.CONNECTION_FORCE_DISCONNECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BattleNetSessionManagerTest {

    private final BattleNetServiceAccountRepository repository = mock(BattleNetServiceAccountRepository.class);
    private final BattleNetLoginCoordinator coordinator = mock(BattleNetLoginCoordinator.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<FakeBgsChannel> channels = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private TicketException mintFailure;

    private BattleNetServiceAccount bot1;
    private BattleNetServiceAccount bot2;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bot1 = account("Bot1");
        bot2 = account("Bot2");
        when(siteRole.isActive()).thenReturn(true);
        when(coordinator.ticketProviderFor(any())).thenReturn(() -> {
            if (mintFailure != null) {
                throw mintFailure;
            }
            return "EU-test";
        });
        when(repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1, bot2));
        when(repository.findById(bot1.getId())).thenReturn(Optional.of(bot1));
        when(repository.findById(bot2.getId())).thenReturn(Optional.of(bot2));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static BattleNetServiceAccount account(String name) {
        BattleNetServiceAccount account = new BattleNetServiceAccount();
        account.setId(UUID.randomUUID());
        account.setEmail(name.toLowerCase() + "@example.test");
        account.setAuthState(AuthState.READY);
        account.setEnabled(true);
        return account;
    }

    private BattleNetSessionManager manager(boolean sessionsEnabled) {
        return new BattleNetSessionManager(repository, coordinator, siteRole, events, registry,
                TransactionOperations.withoutTransaction(), sessionsEnabled, "bgs.test",
                () -> {
                    FakeBgsChannel channel = new FakeBgsChannel();
                    channels.add(channel);
                    return channel;
                },
                scheduler, Runnable::run, Runnable::run, Runnable::run);
    }

    private BattleNetSessionManager started() {
        BattleNetSessionManager manager = manager(true);
        manager.start();
        return manager;
    }

    private static void connect(FakeBgsChannel channel) {
        // server_id is a required field nobody reads: a partial message is enough here
        channel.reply(CONNECTION_CONNECT, ConnectResponse.newBuilder().setCiid("ciid").buildPartial());
    }

    @Test
    void noSessionWithoutTheSessionsFlag() {
        BattleNetSessionManager manager = manager(false);
        manager.start();
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));
        manager.restart(bot1.getId());

        assertThat(channels).isEmpty();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(BattleNetSessionManager.DISABLED);
        verifyNoInteractions(repository);
    }

    @Test
    void noSessionOnStandbySite() {
        when(siteRole.isActive()).thenReturn(false);

        BattleNetSessionManager manager = started();

        assertThat(channels).isEmpty();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(BattleNetSessionManager.DISABLED);
    }

    @Test
    void standbyClosesAllSessionsAndActiveReopensThem() {
        BattleNetSessionManager manager = started();
        assertThat(channels).hasSize(2);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("CONNECTING");

        when(siteRole.isActive()).thenReturn(false);
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));

        assertThat(channels).allMatch(c -> c.closed);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(BattleNetSessionManager.DISABLED);
        assertThat(manager.sessionState(bot2.getId())).isEqualTo(BattleNetSessionManager.DISABLED);

        when(siteRole.isActive()).thenReturn(true);
        manager.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));

        assertThat(channels).hasSize(4);
        assertThat(channels.subList(2, 4)).noneMatch(c -> c.closed);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("CONNECTING");
        assertThat(manager.sessionState(bot2.getId())).isEqualTo("CONNECTING");
    }

    @Test
    void lifecycleStopClosesEverySession() {
        BattleNetSessionManager manager = started();

        manager.stop();

        assertThat(manager.isRunning()).isFalse();
        assertThat(channels).allMatch(c -> c.closed);
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(BattleNetSessionManager.DISABLED);
    }

    @Test
    void onlyEnabledReadyBotsGetASession() {
        BattleNetServiceAccount waitingForCode = account("Bot3");
        waitingForCode.setAuthState(AuthState.CODE_REQUIRED);
        BattleNetServiceAccount disabled = account("Bot4");
        disabled.setEnabled(false);
        when(repository.findById(waitingForCode.getId())).thenReturn(Optional.of(waitingForCode));
        when(repository.findById(disabled.getId())).thenReturn(Optional.of(disabled));

        BattleNetSessionManager manager = started();
        manager.restart(waitingForCode.getId());
        manager.restart(disabled.getId());

        verify(repository).findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY);
        assertThat(channels).hasSize(2);
        assertThat(manager.sessionState(waitingForCode.getId())).isEqualTo(BattleNetSessionManager.DISABLED);
        assertThat(manager.sessionState(disabled.getId())).isEqualTo(BattleNetSessionManager.DISABLED);
    }

    @Test
    void restartReplacesTheSessionAndStopClosesIt() {
        BattleNetSessionManager manager = started();

        manager.restart(bot1.getId());
        assertThat(channels).hasSize(3);
        assertThat(channels.getFirst().closed).isTrue();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo("CONNECTING");

        manager.stop(bot1.getId());
        assertThat(channels.get(2).closed).isTrue();
        assertThat(manager.sessionState(bot1.getId())).isEqualTo(BattleNetSessionManager.DISABLED);
        assertThat(manager.sessionState(bot2.getId())).isEqualTo("CONNECTING");
    }

    @Test
    void presenceEventUpdatesCurrentGame() {
        BattleNetSessionManager manager = started();

        manager.listenerOf(bot1.getId()).onPresence(new PresenceEvent(42L, "WTCG", 0x57544347, List.of()));

        assertThat(manager.currentGame(42L)).contains("WTCG");
        assertThat(manager.currentGame(43L)).isEmpty();
    }

    @Test
    void gameEndClearsCurrentGame() {
        BattleNetSessionManager manager = started();
        manager.listenerOf(bot1.getId()).onPresence(new PresenceEvent(42L, "WTCG", 0x57544347, List.of()));

        manager.listenerOf(bot1.getId()).onPresence(new PresenceEvent(42L, null, 0, List.of()));

        assertThat(manager.currentGame(42L)).isEmpty();
    }

    @Test
    void closingASessionForgetsTheGamesItReported() {
        BattleNetSessionManager manager = started();
        manager.listenerOf(bot1.getId()).onPresence(new PresenceEvent(42L, "WTCG", 0x57544347, List.of()));
        manager.listenerOf(bot2.getId()).onPresence(new PresenceEvent(77L, "Pro", 0x50726F, List.of()));

        manager.stop(bot1.getId());

        assertThat(manager.currentGame(42L)).isEmpty();
        assertThat(manager.currentGame(77L)).contains("Pro");
    }

    @Test
    void sessionReplacedIsCountedAndNotRestarted() {
        BattleNetSessionManager manager = started();

        channels.getFirst().push(CONNECTION_FORCE_DISCONNECT,
                DisconnectNotification.newBuilder().setErrorCode(60).build());

        assertThat(manager.sessionState(bot1.getId())).isEqualTo("STOPPED_REPLACED");
        assertThat(registry.counter(BattleNetSessionManager.REPLACED_COUNTER, "account", bot1.getId().toString()).count())
                .isEqualTo(1.0);
        assertThat(channels).hasSize(2); // no new channel for Bot1
        // tagged by the account id, never its e-mail
        assertThat(registry.find(BattleNetSessionManager.REPLACED_COUNTER).tag("account", bot1.getEmail()).counter())
                .isNull();
    }

    @Test
    void codeRequiredLeavesTheAccountInCodeRequired() {
        bot1.setAuthState(AuthState.CODE_REQUIRED);
        mintFailure = new TicketException("2FA code required", true);
        BattleNetSessionManager manager = started();

        connect(channels.getFirst());

        assertThat(manager.sessionState(bot1.getId())).isEqualTo("STOPPED_CODE_REQUIRED");
        assertThat(registry.counter(BattleNetSessionManager.ADMIN_ACTION_COUNTER, "account", bot1.getId().toString()).count())
                .isEqualTo(1.0);
        assertThat(bot1.getAuthState()).isEqualTo(AuthState.CODE_REQUIRED);
        verify(repository, never()).save(any());
    }

    @Test
    void onReadyFillsBattleTagAndAccountId() {
        BattleNetSessionManager manager = started();

        manager.listenerOf(bot1.getId()).onReady(555L, "Bot1#1234");

        assertThat(bot1.getBattleTag()).isEqualTo("Bot1#1234");
        assertThat(bot1.getBnetAccountId()).isEqualTo(555L);
        verify(repository).save(bot1);
    }

    @Test
    void friendEventsAreRepublished() {
        BattleNetSessionManager manager = started();
        FriendEvent event = new FriendEvent.FriendAdded(42L, "Streamer#1234");

        manager.listenerOf(bot1.getId()).onFriend(event);

        verify(events).publishEvent(new BattleNetFriendEventReceived(bot1.getId(), event));
    }

    @Test
    void withClientFailsFastWhenNotReady() {
        BattleNetSessionManager manager = started();

        CompletableFuture<Void> connecting = manager.withClient(bot1.getId(), client -> client.sendInvitation("A#1"));
        CompletableFuture<Void> unknown = manager.withClient(UUID.randomUUID(), client -> client.sendInvitation("A#1"));

        assertThat(connecting).isCompletedExceptionally();
        assertThat(unknown).isCompletedExceptionally();
        assertThat(connecting.exceptionNow()).isInstanceOf(IllegalStateException.class);
    }
}
