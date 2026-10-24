package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.repository.debug.MemberDebugSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deletes the exchanges, and the session, of members whose debug mode ended more than 30 days ago. */
@Slf4j
@Component
public class DebugPurgeJob {

    private static final Duration RETENTION_AFTER_DISABLE = Duration.ofDays(30);

    private final MemberDebugSessionRepository sessions;
    private final DebugExchangeRepository exchanges;
    private final Clock clock;

    @Autowired
    public DebugPurgeJob(MemberDebugSessionRepository sessions, DebugExchangeRepository exchanges) {
        this(sessions, exchanges, Clock.systemUTC());
    }

    DebugPurgeJob(MemberDebugSessionRepository sessions, DebugExchangeRepository exchanges, Clock clock) {
        this.sessions = sessions;
        this.exchanges = exchanges;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void run() {
        Instant cutoff = Instant.now(clock).minus(RETENTION_AFTER_DISABLE);
        List<MemberDebugSession> expired = sessions.findByActiveFalseAndDisabledAtBefore(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        List<UUID> userIds = expired.stream().map(MemberDebugSession::getUserId).toList();
        exchanges.deleteByUserIdIn(userIds);
        sessions.deleteAll(expired);
        log.info("Purged the debug records of {} member(s)", expired.size());
    }
}
