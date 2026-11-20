package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.repository.stream.StreamCategorySegmentRepository;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Drops the timelines of past lives once a day: they are only read for the recap that follows a raid. */
@Slf4j
@Component
public class SessionTimelinePurgeJob {

    static final Duration RETENTION = Duration.ofHours(24);

    private final StreamCategorySegmentRepository segments;
    private final SiteRole siteRole;
    private final Clock clock;

    @Autowired
    public SessionTimelinePurgeJob(StreamCategorySegmentRepository segments, SiteRole siteRole) {
        this(segments, siteRole, Clock.systemUTC());
    }

    SessionTimelinePurgeJob(StreamCategorySegmentRepository segments, SiteRole siteRole, Clock clock) {
        this.segments = segments;
        this.siteRole = siteRole;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void run() {
        if (!siteRole.isActive()) {
            return;
        }
        int deleted = segments.deleteEndedBefore(clock.instant().minus(RETENTION));
        if (deleted > 0) {
            log.info("Purged {} timeline periods of past lives", deleted);
        }
    }
}
