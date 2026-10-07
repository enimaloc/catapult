package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.getter.SteamGameGetter;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One detection cycle: the events it publishes, and how getter prefetches are awaited and cleared. */
class SchedulerServiceCycleTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final GameGetterChain chain = mock(GameGetterChain.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BindingService bindings = mock(BindingService.class);
    private final SteamGameGetter steam = mock(SteamGameGetter.class);
    private final MinecraftPresenceGetter minecraft = mock(MinecraftPresenceGetter.class);
    private final SchedulerService scheduler = new SchedulerService(accounts, chain, gameState, events,
            new SimpleMeterRegistry(), Optional.of(steam), Optional.of(minecraft), bindings);
    private final DetectedGame portal = new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2");
    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(steam.prefetchBatch(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(minecraft.prefetchBatch()).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void aNewGame_isRecordedAndAnnounced() {
        when(chain.resolve(user)).thenReturn(Optional.of(portal));
        when(gameState.hasChanged(user, portal)).thenReturn(true);

        scheduler.triggerManualCheck(user);

        verify(gameState).updateState(user, portal);
        verify(events).publishEvent(any(GameDetectedEvent.class));
        verify(steam).clearCycleCache();
        verify(minecraft).clearCycleCache();
    }

    @Test
    void theSameGame_isNotAnnouncedAgain() {
        when(chain.resolve(user)).thenReturn(Optional.of(portal));
        when(gameState.hasChanged(user, portal)).thenReturn(false);

        scheduler.triggerManualCheck(user);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void stoppingToPlay_isAnnouncedOnce() {
        when(chain.resolve(user)).thenReturn(Optional.empty());
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.of(portal), Optional.empty());

        scheduler.triggerManualCheck(user);
        scheduler.triggerManualCheck(user);

        verify(gameState).clearState(user);
        verify(events).publishEvent(any(NoGameDetectedEvent.class));
    }

    @Test
    void failedPrefetches_doNotStopTheCycle() {
        when(steam.prefetchBatch(anyList())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("steam down")));
        when(minecraft.prefetchBatch()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("mc down")));
        when(chain.resolve(user)).thenReturn(Optional.empty());
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());

        scheduler.triggerManualCheck(user);

        verify(chain).resolve(user);
    }

    @Test
    void slowPrefetches_areCancelledAfterFiveSeconds() {
        CompletableFuture<Void> neverDone = new CompletableFuture<>();
        when(steam.prefetchBatch(anyList())).thenReturn(neverDone);
        when(chain.resolve(user)).thenReturn(Optional.empty());
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());

        scheduler.triggerManualCheck(user);

        assertThat(neverDone).isCancelled();
    }

    @Test
    void anInterruptedWait_keepsTheInterruptFlag() {
        when(steam.prefetchBatch(anyList())).thenReturn(new CompletableFuture<>());
        when(minecraft.prefetchBatch()).thenReturn(new CompletableFuture<>());
        when(chain.resolve(user)).thenReturn(Optional.empty());
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());

        Thread.currentThread().interrupt();
        try {
            scheduler.triggerManualCheck(user);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void onlySteamLinkedUsers_arePrefetchedFromSteam() {
        UserAccount linked = new UserAccount();
        linked.setId(UUID.randomUUID());
        linked.setSteamId("7656");
        when(accounts.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(user, linked));
        when(chain.resolve(any())).thenReturn(Optional.empty());
        when(gameState.getLastKnownGame(any())).thenReturn(Optional.empty());

        scheduler.poll();

        verify(steam).prefetchBatch(List.of(linked));
    }

    @Test
    void incompleteBindingRefreshFailures_areSwallowed() {
        doThrow(new IllegalStateException("db down")).when(bindings).refreshIncompleteBindings();

        scheduler.onStartup();
        scheduler.retryIncompleteBindings();
    }
}
