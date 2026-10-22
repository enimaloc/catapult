package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MockTwitchEventSubServiceTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final MockTwitchEventSubService service =
            new MockTwitchEventSubService(mock(StreamStateService.class), publisher);

    @Test
    void raidOut_publishesAnOutgoingRaid() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        service.raidOut(user, "509658");

        ArgumentCaptor<ChannelRaidedOutEvent> captor = ArgumentCaptor.forClass(ChannelRaidedOutEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getCurrentCategoryId()).isEqualTo("509658");
    }
}
