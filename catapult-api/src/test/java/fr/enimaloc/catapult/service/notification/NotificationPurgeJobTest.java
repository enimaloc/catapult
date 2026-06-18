package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.Notification;
import fr.enimaloc.catapult.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPurgeJobTest {

    @Mock NotificationRepository repo;

    @Test
    void run_deletesExpiredOlderThan30Days() {
        Notification n = new Notification();
        n.setExpiresAt(Instant.now().minus(31, ChronoUnit.DAYS));
        when(repo.findByExpiresAtBefore(any())).thenReturn(List.of(n));

        new NotificationPurgeJob(repo).run();

        ArgumentCaptor<Instant> cap = ArgumentCaptor.forClass(Instant.class);
        verify(repo).findByExpiresAtBefore(cap.capture());
        assertThat(cap.getValue()).isBefore(Instant.now().minus(29, ChronoUnit.DAYS));
        verify(repo).deleteAll(List.of(n));
    }
}
