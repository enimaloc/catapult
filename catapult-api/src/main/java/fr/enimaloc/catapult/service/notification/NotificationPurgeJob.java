package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.Notification;
import fr.enimaloc.catapult.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPurgeJob {

    private static final Duration RETENTION_AFTER_EXPIRATION = Duration.ofDays(30);

    private final NotificationRepository repo;

    @Scheduled(cron = "0 0 3 * * *")
    public void run() {
        Instant cutoff = Instant.now().minus(RETENTION_AFTER_EXPIRATION);
        List<Notification> rows = repo.findByExpiresAtBefore(cutoff);
        if (!rows.isEmpty()) {
            repo.deleteAll(rows);
            log.info("Purged {} expired notifications", rows.size());
        }
    }
}
