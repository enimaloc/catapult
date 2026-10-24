package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomBindingActivationsTest {

    @Mock private CustomBindingActivationRepository repository;
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
        when(experimentService.evaluateGate(user, "custom.bindings")).thenReturn(false);

        assertThat(activations.active(user)).isEmpty();
        verifyNoInteractions(repository);
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
        when(repository.existsById(user.getId())).thenReturn(true);

        assertThat(activations.clear(user)).isTrue();

        verify(repository).deleteById(user.getId());
        verify(channelEventPublisher).customBindingChanged(user.getId(), null);
        verifyNoInteractions(experimentService);
    }

    @Test
    void clear_withNothingActive_publishesNothing() {
        when(repository.existsById(user.getId())).thenReturn(false);

        assertThat(activations.clear(user)).isFalse();

        verify(repository, never()).deleteById(any());
        verifyNoInteractions(channelEventPublisher);
    }
}
