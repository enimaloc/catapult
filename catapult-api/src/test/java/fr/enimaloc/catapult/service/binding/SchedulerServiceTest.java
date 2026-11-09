package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.site.SiteRoles;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.DetectionTimeoutException;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.getter.steam.SteamGameGetter;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.premium.PremiumService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock GameGetterChain gameGetterChain;
    @Mock GameStateService gameStateService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BindingService bindingService;

    private SimpleMeterRegistry registry;
    private SchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        schedulerService = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.empty(), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.active(), PollCadences.always());
    }

    @Test
    void poll_recordsOneDurationSample_whenNoUsers() {
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of());

        schedulerService.poll();

        assertThat(registry.get("catapult.scheduler.poll.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void poll_countsEachProcessedUser() {
        UserAccount u1 = new UserAccount(); u1.setId(UUID.randomUUID());
        UserAccount u2 = new UserAccount(); u2.setId(UUID.randomUUID());
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u1, u2));
        when(gameGetterChain.resolve(any())).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(any())).thenReturn(Optional.empty());

        schedulerService.poll();

        assertThat(registry.get("catapult.scheduler.users.polled").counter().count())
            .isEqualTo(2.0);
    }

    @Test
    void poll_countsUserEvenWhenProcessUserThrows() {
        UserAccount u1 = new UserAccount(); u1.setId(UUID.randomUUID());
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u1));
        when(gameGetterChain.resolve(any())).thenThrow(new RuntimeException("simulated error"));

        schedulerService.poll();

        assertThat(registry.get("catapult.scheduler.users.polled").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void poll_keepsTheUsersState_whenAProviderTimesOut() {
        UserAccount u1 = new UserAccount(); u1.setId(UUID.randomUUID());
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u1));
        when(gameGetterChain.resolve(any()))
            .thenThrow(new DetectionTimeoutException("Steam", new java.util.concurrent.TimeoutException()));

        schedulerService.poll();

        verify(gameStateService, never()).markObserved(any());
        verify(gameStateService, never()).clearState(any());
        verify(gameStateService, never()).updateState(any(), any());
        verifyNoInteractions(eventPublisher);
        assertThat(registry.get("catapult.scheduler.detection.timeouts").counter().count()).isEqualTo(1.0);
    }

    @Test
    void poll_withSteamGetter_callsPrefetchBatch() {
        SteamGameGetter steamGetter = mock(SteamGameGetter.class);
        when(steamGetter.prefetchBatch(any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of());

        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.of(steamGetter), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.active(), PollCadences.always());

        service.poll();

        verify(steamGetter).prefetchBatch(any());
        verify(steamGetter).clearCycleCache();
    }

    @Test
    void triggerManualCheck_processesOnlyGivenUser() {
        UserAccount user = new UserAccount(); user.setId(UUID.randomUUID());
        when(gameGetterChain.resolve(user)).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        schedulerService.triggerManualCheck(user);

        assertThat(registry.get("catapult.scheduler.manual-checks").counter().count())
            .isEqualTo(1.0);
        verify(userAccountRepository, org.mockito.Mockito.never())
            .findByBotEnabledTrueAndStatus(any());
    }

    @Test
    void triggerManualCheck_withSteamGetter_prefetchesOnlyThatUser() {
        SteamGameGetter steamGetter = mock(SteamGameGetter.class);
        when(steamGetter.prefetchBatch(any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        UserAccount user = new UserAccount(); user.setId(UUID.randomUUID());
        user.setSteamId("steam-1");

        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.of(steamGetter), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.active(), PollCadences.always());
        when(gameGetterChain.resolve(user)).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        service.triggerManualCheck(user);

        verify(steamGetter).prefetchBatch(List.of(user));
        verify(steamGetter).clearCycleCache();
    }

    @Test
    void poll_skipsAccountsNotDue() {
        MockEnvironment env = new MockEnvironment()
            .withProperty(PollCadence.PREMIUM_KEY, "30")
            .withProperty(PollCadence.FREE_KEY, "60");
        PollCadence cadence = new PollCadence(env, mock(PremiumService.class));
        UserAccount recent = new UserAccount(); recent.setId(UUID.randomUUID()); recent.setSteamId("steam-1");
        UserAccount stale = new UserAccount(); stale.setId(UUID.randomUUID()); stale.setSteamId("steam-2");
        cadence.markPolled(recent.getId(), Instant.now().minusSeconds(30));
        cadence.markPolled(stale.getId(), Instant.now().minusSeconds(60));
        SteamGameGetter steamGetter = mock(SteamGameGetter.class);
        when(steamGetter.prefetchBatch(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(recent, stale));
        when(gameGetterChain.resolve(any())).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(any())).thenReturn(Optional.empty());
        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.of(steamGetter), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.active(),
            cadence);

        service.poll();

        verify(steamGetter).prefetchBatch(List.of(stale));
        verify(gameGetterChain).resolve(stale);
        verify(gameGetterChain, never()).resolve(recent);
        assertThat(cadence.isDue(stale, Instant.now())).isFalse();
    }

    @Test
    void triggerManualCheck_ignoresCadence() {
        PollCadence cadence = mock(PollCadence.class);
        UserAccount user = new UserAccount(); user.setId(UUID.randomUUID());
        when(gameGetterChain.resolve(user)).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());
        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.empty(), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.active(),
            cadence);

        service.triggerManualCheck(user);

        verify(gameGetterChain).resolve(user);
        verifyNoInteractions(cadence);
    }

    @Test
    void pollTrigger_firstRunsNow_thenEveryTick() {
        PollCadence cadence = mock(PollCadence.class);
        when(cadence.tickSeconds()).thenReturn(30L, 20L);
        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.empty(), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.active(),
            cadence);
        Instant t0 = Instant.parse("2026-11-04T12:00:00Z");
        SimpleTriggerContext context = new SimpleTriggerContext(Clock.fixed(t0, ZoneOffset.UTC));
        Trigger trigger = service.pollTrigger();

        assertThat(trigger.nextExecution(context)).isEqualTo(t0);
        context.update(t0, t0, t0.plusSeconds(2));
        assertThat(trigger.nextExecution(context)).isEqualTo(t0.plusSeconds(30));
        // Re-read on each run: an override applies from the next tick
        assertThat(trigger.nextExecution(context)).isEqualTo(t0.plusSeconds(20));
    }

    @Test
    void onStartup_callsRefreshIncompleteBindings() {
        schedulerService.onStartup();

        verify(bindingService).refreshIncompleteBindings();
    }

    @Test
    void retryIncompleteBindings_callsRefreshIncompleteBindings() {
        schedulerService.retryIncompleteBindings();

        verify(bindingService).refreshIncompleteBindings();
    }

    @Test
    void scheduler_skips_mock_accounts_without_simulation() {
        UserAccount real = new UserAccount(); real.setId(UUID.randomUUID()); real.setTwitchId("12345");
        UserAccount fictitious = new UserAccount(); fictitious.setId(UUID.randomUUID()); fictitious.setTwitchId("mock-1");
        SteamGameGetter steamGetter = mock(SteamGameGetter.class);
        when(steamGetter.prefetchBatch(any())).thenReturn(CompletableFuture.completedFuture(null));
        real.setSteamId("steam-1");
        fictitious.setSteamId("steam-2");
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(real, fictitious));
        when(gameGetterChain.resolve(any())).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(any())).thenReturn(Optional.empty());
        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.of(steamGetter), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.active(), PollCadences.always());

        service.poll();

        verify(gameGetterChain).resolve(real);
        verify(gameGetterChain, never()).resolve(fictitious);
        verify(steamGetter).prefetchBatch(List.of(real));
    }

    @Test
    void scheduler_polls_mock_accounts_under_simulation() {
        UserAccount fictitious = new UserAccount(); fictitious.setId(UUID.randomUUID()); fictitious.setTwitchId("mock-1");
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(fictitious));
        when(gameGetterChain.resolve(any())).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(any())).thenReturn(Optional.empty());
        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.empty(), Optional.empty(), bindingService, SimulationGates.of(mock(SimulationGate.class)), SiteRoles.active(), PollCadences.always());

        service.poll();

        verify(gameGetterChain).resolve(fictitious);
    }

    @Test
    void poll_doesNothingOnStandby() {
        SchedulerService standby = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.empty(), Optional.empty(), bindingService, SimulationGates.none(), SiteRoles.standby(), PollCadences.always());

        standby.poll();

        verifyNoInteractions(gameGetterChain, eventPublisher, userAccountRepository);
    }
}
