package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * A streamer's active custom binding, on its own so the detection path ({@code GameEventListener}) and the game
 * binding edits ({@code BindingService}) can consult it without depending on {@link CustomBindingService}, which
 * itself depends on {@code GameEventListener}. Depends only on its repository, the experiments and the channel
 * events: keep it that way, or the cycle comes back.
 */
@Service
@RequiredArgsConstructor
public class CustomBindingActivations {

    private final CustomBindingActivationRepository repository;
    private final ExperimentService experimentService;
    private final ChannelEventPublisher channelEventPublisher;

    /** The active custom binding; none while the streamer is outside the experiment, as if it did not exist. */
    public Optional<CustomBindingActivation> active(UserAccount user) {
        if (!experimentService.evaluateGate(user, CustomBindingService.EXPERIMENT_KEY)) return Optional.empty();
        return repository.findById(user.getId());
    }

    /** {@code binding} is now the active one, replacing any other. */
    public void record(UserAccount user, CustomBinding binding) {
        repository.save(new CustomBindingActivation(user.getId(), binding, Instant.now()));
        channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
    }

    /**
     * Forgets the active custom binding, whatever the experiment says (the end of a stream clears it for everyone).
     * Publishes the change only when there was one; tells whether there was.
     */
    public boolean clear(UserAccount user) {
        if (!repository.existsById(user.getId())) return false;
        repository.deleteById(user.getId());
        channelEventPublisher.customBindingChanged(user.getId(), null);
        return true;
    }
}
