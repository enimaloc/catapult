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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A streamer's active custom binding, on its own so the detection path ({@code GameEventListener}) and the game
 * binding edits ({@code BindingService}) can consult it without depending on {@link CustomBindingService}, which
 * itself depends on {@code GameEventListener}. Depends only on its repositories (activations and schedule refusals), the experiments and
 * the channel events: keep it that way, or the cycle comes back.
 */
@Service
@RequiredArgsConstructor
public class CustomBindingActivations {

    private final CustomBindingActivationRepository repository;
    private final CustomBindingScheduleRefusalRepository refusals;
    private final ExperimentService experimentService;
    private final ChannelEventPublisher channelEventPublisher;

    /**
     * The active custom binding; none while the streamer is outside the experiment, as if it did not exist. The gate
     * is evaluated only when a binding is active: evaluating it enrols the streamer in the experiment, which every
     * detection would otherwise do.
     */
    public Optional<CustomBindingActivation> active(UserAccount user) {
        return repository.findById(user.getId())
                .filter(activation -> experimentService.evaluateGate(user, CustomBindingService.EXPERIMENT_KEY));
    }

    /** The recorded activation, without evaluating the experiment: the scheduler checks it itself beforehand. */
    public Optional<CustomBindingActivation> current(UserAccount user) {
        return repository.findById(user.getId());
    }

    /**
     * {@code binding} is now the active one, set by hand. Replacing a scheduled activation refuses its occurrence,
     * unless it is the same binding: that one stays scheduled, so it still ends with its occurrence.
     */
    public void record(UserAccount user, CustomBinding binding) {
        Optional<CustomBindingActivation> previous = repository.findById(user.getId());
        if (previous.filter(activation -> activation.getOrigin() == CustomBindingActivation.Origin.SCHEDULED
                && activation.getCustomBinding().getId().equals(binding.getId())).isPresent()) {
            repository.save(previous.get());
            channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
            return;
        }
        previous.ifPresent(this::refuse);
        repository.save(new CustomBindingActivation(user.getId(), binding, Instant.now()));
        channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
    }

    /** {@code binding} is now the active one, set by the schedule's {@code occurrence}. */
    public void recordScheduled(UserAccount user, CustomBinding binding, UUID scheduleId, Occurrence occurrence) {
        repository.findById(user.getId()).ifPresent(this::refuse);
        CustomBindingActivation activation = new CustomBindingActivation(user.getId(), binding, Instant.now());
        activation.setOrigin(CustomBindingActivation.Origin.SCHEDULED);
        activation.setScheduleId(scheduleId);
        activation.setOccurrenceStart(occurrence.start());
        activation.setOccurrenceEnd(occurrence.end());
        repository.save(activation);
        channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
    }

    /**
     * Forgets the active custom binding, whatever the experiment says; a scheduled occurrence it came from is
     * refused, as the streamer (or a detection) stopped it. Publishes the change only when there was one; tells
     * whether there was.
     */
    public boolean clear(UserAccount user) {
        return clear(user, true);
    }

    /** {@link #clear} without refusing the occurrence: the end of a stream, or of an occurrence, is not a refusal. */
    public boolean clearWithoutRefusal(UserAccount user) {
        return clear(user, false);
    }

    private boolean clear(UserAccount user, boolean refuse) {
        if (refuse) {
            Optional<CustomBindingActivation> previous = repository.findById(user.getId());
            if (previous.isEmpty()) return false;
            refuse(previous.get());
        } else if (!repository.existsById(user.getId())) {
            return false;
        }
        repository.deleteById(user.getId());
        channelEventPublisher.customBindingChanged(user.getId(), null);
        return true;
    }

    /** The occurrence is not activated again; nothing to refuse for a manual activation or a deleted slot. */
    private void refuse(CustomBindingActivation activation) {
        if (activation.getOrigin() != CustomBindingActivation.Origin.SCHEDULED
                || activation.getScheduleId() == null) return;
        refusals.save(new CustomBindingScheduleRefusal(activation.getScheduleId(),
                activation.getOccurrenceStart(), activation.getOccurrenceEnd()));
    }
}
