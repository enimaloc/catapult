package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.CustomBindingScheduleRefusal;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRefusalRepository;
import fr.enimaloc.catapult.service.binding.schedule.ScheduleOccurrences.Occurrence;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomBindingActivationsTest {

    @Mock private CustomBindingActivationRepository repository;
    @Mock private CustomBindingScheduleRefusalRepository refusals;
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
}
