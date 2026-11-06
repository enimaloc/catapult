package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingScheduleRefusal;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRefusalRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRepository;
import fr.enimaloc.catapult.service.binding.schedule.ScheduleOccurrences.Occurrence;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.premium.PremiumService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomBindingActivationsTest {

    @Mock private CustomBindingActivationRepository repository;
    @Mock private CustomBindingScheduleRefusalRepository refusals;
    @Mock private CustomBindingScheduleRepository schedules;
    @Mock private StreamStateService streamStateService;
    @Mock private ExperimentService experimentService;
    @Mock private ChannelEventPublisher channelEventPublisher;

    @InjectMocks private CustomBindingActivations activations;

    private UserAccount user;
    private CustomBinding art;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        art = new CustomBinding();
        art.setId(UUID.randomUUID());
        art.setUser(user);
        art.setName("Art");
    }

    @Test
    void active_insideTheExperiment_isTheRecordedOne() {
        CustomBindingActivation activation = new CustomBindingActivation(user.getId(), art, Instant.EPOCH);
        when(experimentService.evaluateGate(user, "custom.bindings")).thenReturn(true);
        when(repository.findById(user.getId())).thenReturn(Optional.of(activation));

        assertThat(activations.active(user)).contains(activation);
    }

    @Test
    void active_outsideTheExperiment_isNone_evenWithOneRecorded() {
        when(repository.findById(user.getId()))
                .thenReturn(Optional.of(new CustomBindingActivation(user.getId(), art, Instant.EPOCH)));
        when(experimentService.evaluateGate(user, "custom.bindings")).thenReturn(false);

        assertThat(activations.active(user)).isEmpty();
    }

    @Test
    void active_withNoneRecorded_neverEvaluatesTheGate() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(activations.active(user)).isEmpty();
        // Evaluating the gate enrols the streamer in the experiment
        verifyNoInteractions(experimentService);
    }

    @Test
    void record_savesItUnderTheStreamer_andPublishesIt() {
        activations.record(user, art);

        ArgumentCaptor<CustomBindingActivation> saved = ArgumentCaptor.forClass(CustomBindingActivation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(saved.getValue().getCustomBinding()).isSameAs(art);
        assertThat(saved.getValue().getActivatedAt()).isNotNull();
        verify(channelEventPublisher).customBindingChanged(user.getId(), art.getId());
    }

    @Test
    void clear_deletesIt_andPublishesNoneActive() {
        when(repository.findById(user.getId()))
                .thenReturn(Optional.of(new CustomBindingActivation(user.getId(), art, Instant.EPOCH)));

        assertThat(activations.clear(user)).isTrue();

        verify(repository).deleteById(user.getId());
        verify(channelEventPublisher).customBindingChanged(user.getId(), null);
        verifyNoInteractions(experimentService);
    }

    @Test
    void clear_withNothingActive_publishesNothing() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(activations.clear(user)).isFalse();

        verify(repository, never()).deleteById(any());
        verifyNoInteractions(channelEventPublisher);
    }

    private static final Instant START = Instant.parse("2026-11-06T19:00:00Z");
    private static final Instant END = Instant.parse("2026-11-06T21:00:00Z");

    private CustomBindingActivation scheduled(CustomBinding binding, UUID scheduleId) {
        CustomBindingActivation activation = new CustomBindingActivation(user.getId(), binding, Instant.EPOCH);
        activation.setOrigin(CustomBindingActivation.Origin.SCHEDULED);
        activation.setScheduleId(scheduleId);
        activation.setOccurrenceStart(START);
        activation.setOccurrenceEnd(END);
        return activation;
    }

    @Test
    void manualSwitchRefusesTheScheduledOccurrence() {
        UUID schedule = UUID.randomUUID();
        CustomBinding other = new CustomBinding();
        other.setId(UUID.randomUUID());
        when(repository.findById(user.getId())).thenReturn(Optional.of(scheduled(art, schedule)));

        activations.record(user, other);

        verify(refusals).save(argThat((CustomBindingScheduleRefusal r) -> r.getScheduleId().equals(schedule)
                && r.getOccurrenceStart().equals(START) && r.getOccurrenceEnd().equals(END)));
        ArgumentCaptor<CustomBindingActivation> saved = ArgumentCaptor.forClass(CustomBindingActivation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrigin()).isEqualTo(CustomBindingActivation.Origin.MANUAL);
        assertThat(saved.getValue().getCustomBinding()).isSameAs(other);
    }

    @Test
    void reTriggeringTheScheduledBindingKeepsItScheduled() {
        lenient().when(streamStateService.isLive(user)).thenReturn(true);
        lenient().when(schedules.findByCustomBindingUserId(user.getId())).thenReturn(List.of(slot(-1, 1, false)));
        when(repository.findById(user.getId())).thenReturn(Optional.of(scheduled(art, UUID.randomUUID())));

        activations.record(user, art);

        // Left as it is: still scheduled, same occurrence, nothing refused
        verify(repository, never()).save(any());
        verifyNoInteractions(refusals);
        verify(channelEventPublisher).customBindingChanged(user.getId(), art.getId());
    }

    @Test
    void recordScheduled_storesTheOccurrence() {
        UUID schedule = UUID.randomUUID();

        activations.recordScheduled(user, art, schedule, new Occurrence(START, END));

        ArgumentCaptor<CustomBindingActivation> saved = ArgumentCaptor.forClass(CustomBindingActivation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrigin()).isEqualTo(CustomBindingActivation.Origin.SCHEDULED);
        assertThat(saved.getValue().getScheduleId()).isEqualTo(schedule);
        assertThat(saved.getValue().getOccurrenceEnd()).isEqualTo(END);
        verify(channelEventPublisher).customBindingChanged(user.getId(), art.getId());
    }

    @Test
    void clearRefusesAScheduledOccurrence() {
        UUID schedule = UUID.randomUUID();
        when(repository.findById(user.getId())).thenReturn(Optional.of(scheduled(art, schedule)));

        assertThat(activations.clear(user)).isTrue();

        verify(refusals).save(argThat((CustomBindingScheduleRefusal r) -> r.getScheduleId().equals(schedule)));
        verify(repository).deleteById(user.getId());
    }

    @Test
    void clearWithoutRefusalDoesNot() {
        when(repository.existsById(user.getId())).thenReturn(true);

        assertThat(activations.clearWithoutRefusal(user)).isTrue();

        verifyNoInteractions(refusals);
        verify(repository).deleteById(user.getId());
        verify(channelEventPublisher).customBindingChanged(user.getId(), null);
    }

    @Test
    void clearOfADeletedSlotsActivation_refusesNothing() {
        when(repository.findById(user.getId())).thenReturn(Optional.of(scheduled(art, null)));

        activations.clear(user);

        verifyNoInteractions(refusals);
    }

    @Test
    void current_ignoresTheExperiment() {
        CustomBindingActivation activation = new CustomBindingActivation(user.getId(), art, Instant.EPOCH);
        when(repository.findById(user.getId())).thenReturn(Optional.of(activation));

        assertThat(activations.current(user)).contains(activation);
        verifyNoInteractions(experimentService);
    }

    @Test
    void recordScheduledOverAScheduledActivation_refusesNothing() {
        CustomBindingActivation current = scheduled(art, UUID.randomUUID());
        lenient().when(repository.findById(user.getId())).thenReturn(Optional.of(current));

        activations.recordScheduled(user, art, current.getScheduleId(), new Occurrence(START, END));
        activations.recordScheduled(user, art, UUID.randomUUID(), new Occurrence(START, END));

        verifyNoInteractions(refusals);
    }

    @Test
    void clearWithTheLoadedActivation_refusesItWithoutReadingAgain() {
        UUID schedule = UUID.randomUUID();

        activations.clear(user, scheduled(art, schedule));

        verify(refusals).save(argThat((CustomBindingScheduleRefusal r) -> r.getScheduleId().equals(schedule)));
        verify(repository, never()).findById(any());
        verify(repository).deleteById(user.getId());
    }

    /** A one-off slot of {@code art} from {@code fromHours} to {@code toHours} hours from now. */
    private CustomBindingSchedule slot(int fromHours, int toHours, boolean paused) {
        LocalDateTime now = LocalDateTime.now(PremiumService.ZONE).truncatedTo(ChronoUnit.MINUTES);
        CustomBindingSchedule s = new CustomBindingSchedule();
        s.setId(UUID.randomUUID());
        s.setCustomBinding(art);
        s.setStartsAt(now.plusHours(fromHours));
        s.setEndsAt(now.plusHours(toHours));
        s.setPaused(paused);
        return s;
    }

    private void refused(CustomBindingSchedule schedule, int times) {
        verify(refusals, times(times)).save(argThat((CustomBindingScheduleRefusal r) ->
                r.getScheduleId().equals(schedule.getId())));
    }

    @Test
    void manualSwitchWhileLive_refusesEveryRunningOccurrence() {
        // B (scheduled, active) runs inside A: stopping B must not hand the channel back to A
        CustomBindingSchedule a = slot(-2, 2, false);
        CustomBindingSchedule b = slot(-1, 1, false);
        CustomBindingSchedule paused = slot(-1, 1, true);
        CustomBindingSchedule later = slot(1, 2, false);
        CustomBindingActivation active = scheduled(art, b.getId());
        active.setOccurrenceStart(b.getStartsAt().atZone(PremiumService.ZONE).toInstant());
        when(repository.findById(user.getId())).thenReturn(Optional.of(active));
        when(streamStateService.isLive(user)).thenReturn(true);
        when(schedules.findByCustomBindingUserId(user.getId())).thenReturn(List.of(a, b, paused, later));
        CustomBinding other = new CustomBinding();
        other.setId(UUID.randomUUID());

        activations.record(user, other);

        refused(a, 1);
        refused(b, 1);
        refused(paused, 0);
        refused(later, 0);
        verify(repository, times(1)).findById(user.getId());
    }

    @Test
    void manualPickWhileLiveDuringAnOccurrence_refusesIt_evenWithNothingScheduledActive() {
        // The scheduled activation failed, the streamer picked by hand: a restart must not take it back
        CustomBindingSchedule a = slot(-1, 1, false);
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        when(streamStateService.isLive(user)).thenReturn(true);
        when(schedules.findByCustomBindingUserId(user.getId())).thenReturn(List.of(a));

        activations.record(user, art);

        refused(a, 1);
    }

    @Test
    void manualPickOffline_refusesNothing() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        when(streamStateService.isLive(user)).thenReturn(false);

        activations.record(user, art);

        verifyNoInteractions(refusals, schedules);
    }

    @Test
    void clearWhileLive_refusesTheRunningOccurrences_evenWithNothingActive() {
        CustomBindingSchedule a = slot(-1, 1, false);
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        when(streamStateService.isLive(user)).thenReturn(true);
        when(schedules.findByCustomBindingUserId(user.getId())).thenReturn(List.of(a));

        assertThat(activations.clear(user)).isFalse();

        refused(a, 1);
        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void clearWithTheLoadedActivationWhileLive_refusesTheOtherRunningOccurrences() {
        CustomBindingSchedule a = slot(-2, 2, false);
        when(streamStateService.isLive(user)).thenReturn(true);
        when(schedules.findByCustomBindingUserId(user.getId())).thenReturn(List.of(a));

        activations.clear(user, scheduled(art, UUID.randomUUID()));

        refused(a, 1);
        verify(repository, never()).findById(any());
    }

    @Test
    void clearWithoutRefusalWhileLive_refusesNothing() {
        lenient().when(streamStateService.isLive(user)).thenReturn(true);
        when(repository.existsById(user.getId())).thenReturn(true);

        activations.clearWithoutRefusal(user);

        verifyNoInteractions(refusals, schedules);
    }

    @Test
    void recordScheduledWhileLive_refusesNothing() {
        lenient().when(streamStateService.isLive(user)).thenReturn(true);

        activations.recordScheduled(user, art, UUID.randomUUID(), new Occurrence(START, END));

        verifyNoInteractions(refusals, schedules);
    }
}
