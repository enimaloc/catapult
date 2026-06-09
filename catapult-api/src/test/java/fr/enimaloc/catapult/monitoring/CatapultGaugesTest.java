package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.StreamStateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatapultGaugesTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock StreamStateService streamStateService;

    @Test
    void bindTo_registersActiveUsersGauge() {
        when(userAccountRepository.countByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CatapultGauges(userAccountRepository, streamStateService).bindTo(registry);

        assertThat(registry.get("catapult.users.active").gauge().value()).isEqualTo(5.0);
    }

    @Test
    void bindTo_registersLiveStreamsGauge() {
        when(streamStateService.countLive()).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CatapultGauges(userAccountRepository, streamStateService).bindTo(registry);

        assertThat(registry.get("catapult.streams.live").gauge().value()).isEqualTo(3.0);
    }
}
