package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsEventListenerTest {

    @Test
    void stream_duration_recorded_on_offline_after_online() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsEventListener listener = new MetricsEventListener(registry);
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(UUID.randomUUID());

        listener.onStreamOnline(new StreamOnlineEvent(this, user));
        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        assertThat(registry.get("catapult.stream.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void offline_without_online_records_no_duration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsEventListener listener = new MetricsEventListener(registry);
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(UUID.randomUUID());

        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        assertThat(registry.find("catapult.stream.duration").timer()).isNull();
    }
}
