package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.CustomBindingSchedulesChangedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomBindingSchedulesPublisherTest {

    @Mock UserAccountRepository users;
    @Mock CustomBindingViews views;
    @Mock ChannelEventPublisher channelEventPublisher;
    @InjectMocks CustomBindingSchedulesPublisher publisher;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void tellsTheDashboardsTheStateWithItsSchedules() {
        CustomBindingsStateDto state = new CustomBindingsStateDto(true, List.of(), null, null);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(views.stateIfEnabled(user)).thenReturn(state);

        publisher.onSchedulesChanged(new CustomBindingSchedulesChangedEvent(user.getId()));

        verify(channelEventPublisher).customBindingSchedulesUpdated(user.getId(), state);
    }

    @Test
    void tellsNothingOutsideTheExperiment() {
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        publisher.onSchedulesChanged(new CustomBindingSchedulesChangedEvent(user.getId()));

        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void aFailureIsLoggedNotThrown() {
        when(users.findById(user.getId())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> publisher.onSchedulesChanged(new CustomBindingSchedulesChangedEvent(user.getId())))
                .doesNotThrowAnyException();
    }
}
