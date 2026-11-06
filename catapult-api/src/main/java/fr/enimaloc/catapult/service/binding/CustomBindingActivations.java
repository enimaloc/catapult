package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingScheduleRefusal;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRefusalRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRepository;
import fr.enimaloc.catapult.service.binding.schedule.ScheduleOccurrences;
import fr.enimaloc.catapult.service.binding.schedule.ScheduleOccurrences.Occurrence;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.premium.PremiumService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A streamer's active custom binding, on its own so the detection path ({@code GameEventListener}) and the game
 * binding edits ({@code BindingService}) can consult it without depending on {@link CustomBindingService}, which
 * itself depends on {@code GameEventListener}. Depends only on its repositories (activations, schedules and their
 * refusals), the stream state, the experiments and the channel events: keep it that way, or the cycle comes back.
 *
 * <p>A streamer's choice (a binding set by hand, a deactivation, a category picked another way) refuses every
 * scheduled occurrence running at that moment, not only the one active: the schedule does not take the choice back
 * until a later occurrence starts. Only while live: the schedule acts only then, and a choice made offline is
 * replaced when the stream starts.</p>
 */
@Service
@RequiredArgsConstructor
public class CustomBindingActivations {

    private final CustomBindingActivationRepository repository;
    private final CustomBindingScheduleRefusalRepository refusals;
    private final CustomBindingScheduleRepository schedules;
    private final StreamStateService streamStateService;
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
     * {@code binding} is now the active one, set by hand: refuses the occurrences running now (see the class
     * comment), unless it re-triggers the scheduled binding: that one stays scheduled, so it still ends with its
     * occurrence.
     */
    public void record(UserAccount user, CustomBinding binding) {
        Optional<CustomBindingActivation> previous = repository.findById(user.getId());
        if (previous.filter(activation -> activation.getOrigin() == CustomBindingActivation.Origin.SCHEDULED
                && activation.getCustomBinding().getId().equals(binding.getId())).isPresent()) {
            channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
            return;
        }
        refuse(user, previous);
        repository.save(new CustomBindingActivation(user.getId(), binding, Instant.now()));
        channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
    }

    /** {@code binding} is now the active one, set by the schedule's {@code occurrence}. */
    public void recordScheduled(UserAccount user, CustomBinding binding, UUID scheduleId, Occurrence occurrence) {
        // Replacing a scheduled activation is the schedule's own doing, never a refusal
        CustomBindingActivation activation = new CustomBindingActivation(user.getId(), binding, Instant.now());
        activation.setOrigin(CustomBindingActivation.Origin.SCHEDULED);
        activation.setScheduleId(scheduleId);
        activation.setOccurrenceStart(occurrence.start());
        activation.setOccurrenceEnd(occurrence.end());
        repository.save(activation);
        channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
    }

    /**
     * Forgets the active custom binding, whatever the experiment says; the occurrences running now are refused,
     * as the streamer (or a detection) chose something else, even with nothing active (a scheduled activation that
     * failed would otherwise be retried over that choice). Publishes the change only when there was one; tells
     * whether there was.
     */
    public boolean clear(UserAccount user) {
        return clear(user, true);
    }

    /** {@link #clear(UserAccount)} on an activation the caller already loaded, to spare a read. */
    public boolean clear(UserAccount user, CustomBindingActivation loaded) {
        refuse(user, Optional.of(loaded));
        repository.deleteById(user.getId());
        channelEventPublisher.customBindingChanged(user.getId(), null);
        return true;
    }

    /** {@link #clear} without refusing the occurrence: the end of a stream, or of an occurrence, is not a refusal. */
    public boolean clearWithoutRefusal(UserAccount user) {
        return clear(user, false);
    }

    private boolean clear(UserAccount user, boolean refuse) {
        if (refuse) {
            Optional<CustomBindingActivation> previous = repository.findById(user.getId());
            refuse(user, previous);
            if (previous.isEmpty()) return false;
        } else if (!repository.existsById(user.getId())) {
            return false;
        }
        repository.deleteById(user.getId());
        channelEventPublisher.customBindingChanged(user.getId(), null);
        return true;
    }

    /**
     * The occurrence {@code previous} came from, and while live every one running now, are not activated again.
     * Nothing to refuse for a manual activation or a deleted slot.
     */
    private void refuse(UserAccount user, Optional<CustomBindingActivation> previous) {
        Optional<CustomBindingScheduleRefusal> own = previous
                .filter(activation -> activation.getOrigin() == CustomBindingActivation.Origin.SCHEDULED
                        && activation.getScheduleId() != null)
                .map(activation -> new CustomBindingScheduleRefusal(activation.getScheduleId(),
                        activation.getOccurrenceStart(), activation.getOccurrenceEnd()));
        own.ifPresent(refusals::save);
        if (!streamStateService.isLive(user)) return;
        Instant now = Instant.now();
        for (CustomBindingSchedule schedule : schedules.findByCustomBindingUserId(user.getId())) {
            if (schedule.isPaused()) continue;
            ScheduleOccurrences.current(schedule, now, PremiumService.ZONE)
                    .filter(occurrence -> own.filter(refusal -> refusal.getScheduleId().equals(schedule.getId())
                            && refusal.getOccurrenceStart().equals(occurrence.start())).isEmpty())
                    .ifPresent(occurrence -> refusals.save(new CustomBindingScheduleRefusal(schedule.getId(),
                            occurrence.start(), occurrence.end())));
        }
    }
}
