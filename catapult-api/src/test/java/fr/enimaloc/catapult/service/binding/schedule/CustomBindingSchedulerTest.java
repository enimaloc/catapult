package fr.enimaloc.catapult.service.binding.schedule;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation.Origin;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule.Repeat;
import fr.enimaloc.catapult.event.CustomBindingSchedulesChangedEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRefusalRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.premium.PremiumService;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomBindingSchedulerTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 10, 0, 0);

    @Mock CustomBindingScheduleRepository schedules;
    @Mock CustomBindingScheduleRefusalRepository refusals;
    @Mock CustomBindingActivationRepository activationRepository;
    @Mock CustomBindingActivations activations;
    @Mock CustomBindingService customBindingService;
    @Mock StreamStateService streamStateService;
    @Mock PremiumService premiumService;
    @Mock UserAccountRepository users;
    @Mock SiteRole siteRole;

    private final java.util.List<Runnable> handedOff = new java.util.ArrayList<>();
    private final java.util.concurrent.Executor executor = handedOff::add;
    private CustomBindingScheduler scheduler;
    private UserAccount user;
    private CustomBinding binding;
    private final List<CustomBindingSchedule> stored = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        scheduler = new CustomBindingScheduler(schedules, refusals, activationRepository, activations,
                customBindingService, streamStateService, premiumService, users, siteRole, executor,
                Clock.fixed(at(21, 30), ZoneOffset.UTC));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        binding = binding();
        lenient().when(customBindingService.enabled(user)).thenReturn(true);
        lenient().when(streamStateService.isLive(user)).thenReturn(true);
        lenient().when(schedules.findByCustomBindingUserId(user.getId())).thenAnswer(i -> List.copyOf(stored));
        lenient().when(premiumService.canSchedule(eq(user), any(), any())).thenReturn(true);
        lenient().when(activations.current(user)).thenReturn(Optional.empty());
        lenient().when(customBindingService.activateScheduled(any(), any(), any(), any()))
                .thenReturn(ActivationResult.APPLIED);
    }

    private CustomBinding binding() {
        CustomBinding b = new CustomBinding();
        b.setId(UUID.randomUUID());
        b.setUser(user);
        b.setTwitchGameId("1");
        return b;
    }

    /** Europe/Paris is UTC+1 in November: local 20:00 is 19:00Z. */
    private static Instant at(int hour, int minute) {
        return DAY.plusHours(hour - 1).plusMinutes(minute).toInstant(ZoneOffset.UTC);
    }

    private CustomBindingSchedule slot(CustomBinding b, int fromHour, int toHour) {
        CustomBindingSchedule s = new CustomBindingSchedule();
        s.setId(UUID.randomUUID());
        s.setCustomBinding(b);
        s.setStartsAt(DAY.plusHours(fromHour));
        s.setEndsAt(DAY.plusHours(toHour));
        s.setRepeat(Repeat.NONE);
        stored.add(s);
        return s;
    }

    private CustomBindingActivation active(Origin origin, Instant activatedAt, CustomBindingSchedule s, int fromHour) {
        CustomBindingActivation a = new CustomBindingActivation(user.getId(), s == null ? binding : s.getCustomBinding(),
                activatedAt);
        a.setOrigin(origin);
        if (s != null) {
            a.setScheduleId(s.getId());
            a.setOccurrenceStart(at(fromHour, 0));
        }
        when(activations.current(user)).thenReturn(Optional.of(a));
        return a;
    }

    private void evaluateAt(int hour, int minute) {
        scheduler.evaluate(user, at(hour, minute));
    }

    @Test
    void activatesTheDueBindingWhileLive() {
        CustomBindingSchedule s = slot(binding, 20, 23);

        evaluateAt(21, 30);

        verify(customBindingService).activateScheduled(eq(user), eq(binding), eq(s.getId()),
                eq(new ScheduleOccurrences.Occurrence(at(20, 0), at(23, 0))));
    }

    @Test
    void doesNothingOffline() {
        slot(binding, 20, 23);
        when(streamStateService.isLive(user)).thenReturn(false);

        evaluateAt(21, 30);

        verify(customBindingService, never()).activateScheduled(any(), any(), any(), any());
        verify(customBindingService, never()).endScheduled(any());
    }

    @Test
    void doesNothingOutsideTheExperiment() {
        slot(binding, 20, 23);
        when(customBindingService.enabled(user)).thenReturn(false);

        evaluateAt(21, 30);

        verify(customBindingService, never()).activateScheduled(any(), any(), any(), any());
        verify(customBindingService, never()).endScheduled(any());
    }

    @Test
    void endsTheScheduledBindingAfterItsOccurrence() {
        CustomBindingSchedule s = slot(binding, 20, 23);
        active(Origin.SCHEDULED, at(20, 0), s, 20);

        evaluateAt(23, 30);

        verify(customBindingService).endScheduled(user);
    }

    @Test
    void leavesAManualChoiceMadeDuringTheOccurrence() {
        CustomBindingSchedule s = slot(binding, 20, 23);
        active(Origin.MANUAL, at(20, 30), null, 0);

        evaluateAt(21, 30);

        verify(customBindingService, never()).activateScheduled(any(), any(), any(), any());
        verify(customBindingService, never()).endScheduled(any());
    }

    @Test
    void replacesAManualChoiceMadeBeforeTheOccurrence() {
        CustomBindingSchedule s = slot(binding, 20, 23);
        active(Origin.MANUAL, at(19, 0), null, 0);

        evaluateAt(21, 30);

        verify(customBindingService).activateScheduled(eq(user), eq(binding), eq(s.getId()), any());
    }

    @Test
    void refusedOccurrenceIsNotReapplied() {
        CustomBindingSchedule s = slot(binding, 20, 23);
        when(refusals.existsByScheduleIdAndOccurrenceStart(s.getId(), at(20, 0))).thenReturn(true);
        active(Origin.MANUAL, at(21, 0), null, 0);

        evaluateAt(21, 30);

        verify(customBindingService, never()).activateScheduled(any(), any(), any(), any());
        verify(customBindingService, never()).endScheduled(any());
    }

    @Test
    void latestStartedOccurrenceWins() {
        CustomBindingSchedule a = slot(binding, 20, 23);
        CustomBinding other = binding();
        CustomBindingSchedule b = slot(other, 21, 22);

        evaluateAt(21, 30);
        verify(customBindingService).activateScheduled(eq(user), eq(other), eq(b.getId()), any());

        active(Origin.SCHEDULED, at(21, 0), b, 21);
        evaluateAt(22, 30);
        verify(customBindingService).activateScheduled(eq(user), eq(binding), eq(a.getId()), any());
    }

    @Test
    void pausedOrSuspendedSchedulesAreIgnored() {
        CustomBindingSchedule paused = slot(binding, 20, 23);
        paused.setPaused(true);
        CustomBinding other = binding();
        CustomBindingSchedule suspended = slot(other, 20, 23);
        suspended.setRepeat(Repeat.DAILY);
        when(premiumService.canSchedule(user, "1", Repeat.DAILY)).thenReturn(false);
        active(Origin.SCHEDULED, at(20, 0), suspended, 20);

        evaluateAt(21, 30);

        verify(customBindingService, never()).activateScheduled(any(), any(), any(), any());
        verify(customBindingService).endScheduled(user);
    }

    @Test
    void deletedScheduleEndsItsActivation() {
        active(Origin.SCHEDULED, at(20, 0), null, 0);

        evaluateAt(21, 30);

        verify(customBindingService).endScheduled(user);
    }

    @Test
    void anActivationWhoseSlotWasDeletedIsReplacedWhenSomethingIsDue() {
        CustomBindingSchedule s = slot(binding, 20, 23);
        active(Origin.SCHEDULED, at(20, 0), null, 0);

        evaluateAt(21, 30);

        verify(customBindingService).activateScheduled(eq(user), eq(binding), eq(s.getId()), any());
    }

    @Test
    void leavesTheActivationOfTheCurrentOccurrenceAlone() {
        CustomBindingSchedule s = slot(binding, 20, 23);
        active(Origin.SCHEDULED, at(20, 0), s, 20);

        evaluateAt(21, 30);

        verify(customBindingService, never()).activateScheduled(any(), any(), any(), any());
        verify(customBindingService, never()).endScheduled(any());
    }

    @Test
    void restartedStreamGetsTheBindingBack() {
        CustomBindingSchedule s = slot(binding, 20, 23);

        scheduler.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(customBindingService).activateScheduled(eq(user), eq(binding), eq(s.getId()), any());
    }

    @Test
    void aChangeEventReevaluatesTheUser() {
        CustomBindingSchedule s = slot(binding, 20, 23);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        scheduler.onSchedulesChanged(new CustomBindingSchedulesChangedEvent(user.getId()));
        // Handed to another thread, where no transaction resource of the committed one is bound
        assertThat(handedOff).hasSize(1);
        verifyNoInteractions(customBindingService);
        handedOff.get(0).run();

        verify(customBindingService).activateScheduled(eq(user), eq(binding), eq(s.getId()), any());
    }

    @Test
    void failedActivationIsRetriedNextTick() {
        slot(binding, 20, 23);
        when(customBindingService.activateScheduled(any(), any(), any(), any()))
                .thenReturn(ActivationResult.BOT_DISABLED, ActivationResult.APPLIED);

        evaluateAt(21, 30);
        evaluateAt(21, 31);

        verify(customBindingService, times(2)).activateScheduled(any(), any(), any(), any());
    }

    @Test
    void tickSkipsWhenTheSiteIsNotActive() {
        when(siteRole.isActive()).thenReturn(false);

        scheduler.tick();

        verifyNoInteractions(refusals, schedules, activationRepository, customBindingService);
    }

    @Test
    void tickPurgesAndEvaluatesTheUnionOfUsersDespiteAFailure() {
        when(siteRole.isActive()).thenReturn(true);
        UUID broken = UUID.randomUUID();
        when(schedules.findUserIdsWithUnpausedSchedules()).thenReturn(List.of(broken, user.getId()));
        when(activationRepository.findUserIdsByOrigin(Origin.SCHEDULED)).thenReturn(List.of(user.getId()));
        when(users.findById(broken)).thenThrow(new IllegalStateException("boom"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        CustomBindingSchedule s = slot(binding, 20, 23);

        scheduler.tick();

        verify(refusals).deleteByOccurrenceEndBefore(at(21, 30));
        verify(customBindingService, times(1)).activateScheduled(eq(user), eq(binding), eq(s.getId()), any());
    }

    @Test
    void concurrentEvaluationsOfTheSameUserActivateOnce() throws Exception {
        CustomBindingSchedule s = slot(binding, 20, 23);
        java.util.concurrent.atomic.AtomicReference<CustomBindingActivation> current =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(activations.current(user)).thenAnswer(i -> Optional.ofNullable(current.get()));
        when(customBindingService.activateScheduled(any(), any(), any(), any())).thenAnswer(i -> {
            Thread.sleep(200);
            CustomBindingActivation a = new CustomBindingActivation(user.getId(), binding, at(21, 30));
            a.setOrigin(Origin.SCHEDULED);
            a.setScheduleId(s.getId());
            a.setOccurrenceStart(at(20, 0));
            current.set(a);
            return ActivationResult.APPLIED;
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> evaluateAt(21, 30));
            var f2 = pool.submit(() -> evaluateAt(21, 30));
            f1.get();
            f2.get();
        } finally {
            pool.shutdownNow();
        }

        verify(customBindingService, times(1)).activateScheduled(any(), any(), any(), any());
    }

    @Test
    void aRejectedHandOffIsLoggedNotThrown() {
        var rejecting = new CustomBindingScheduler(schedules, refusals, activationRepository, activations,
                customBindingService, streamStateService, premiumService, users, siteRole,
                r -> { throw new java.util.concurrent.RejectedExecutionException("full"); },
                Clock.fixed(at(21, 30), ZoneOffset.UTC));

        rejecting.onSchedulesChanged(new CustomBindingSchedulesChangedEvent(user.getId()));

        verifyNoInteractions(customBindingService);
    }
}
