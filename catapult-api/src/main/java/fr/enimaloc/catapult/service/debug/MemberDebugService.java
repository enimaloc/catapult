package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.repository.debug.MemberDebugSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Admin-driven switch of a member's debug mode, and the clearing of what was recorded. */
@Service
public class MemberDebugService {

    /** How long a member's exchanges outlive the end of their debug mode; the purge job applies it. */
    static final Duration RETENTION_AFTER_DISABLE = Duration.ofDays(30);

    private final MemberDebugSessionRepository sessions;
    private final DebugExchangeRepository exchanges;
    private final DebugMemberRegistry registry;
    private final Clock clock;

    @Autowired
    public MemberDebugService(MemberDebugSessionRepository sessions, DebugExchangeRepository exchanges,
                              DebugMemberRegistry registry) {
        this(sessions, exchanges, registry, Clock.systemUTC());
    }

    MemberDebugService(MemberDebugSessionRepository sessions, DebugExchangeRepository exchanges,
                       DebugMemberRegistry registry, Clock clock) {
        this.sessions = sessions;
        this.exchanges = exchanges;
        this.registry = registry;
        this.clock = clock;
    }

    /** Switches the mode on; the recorded exchanges of an earlier session are kept. 409 when already on. */
    @Transactional
    public MemberDebugSession enable(UUID userId, boolean revealSecrets, UUID adminId) {
        MemberDebugSession session = sessions.findById(userId).orElseGet(() -> {
            MemberDebugSession s = new MemberDebugSession();
            s.setUserId(userId);
            return s;
        });
        if (session.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Debug mode already active");
        }
        session.setActive(true);
        session.setRevealSecrets(revealSecrets);
        session.setEnabledBy(adminId);
        session.setEnabledAt(Instant.now(clock));
        session.setDisabledAt(null);
        MemberDebugSession saved = sessions.save(session);
        afterCommit(() -> registry.activate(userId, revealSecrets));
        return saved;
    }

    /** Switches the mode off and starts the 30-day retention of the exchanges. No-op when not active. */
    @Transactional
    public void disable(UUID userId) {
        sessions.findById(userId).filter(MemberDebugSession::isActive).ifPresent(s -> {
            s.setActive(false);
            s.setDisabledAt(Instant.now(clock));
            sessions.save(s);
        });
        afterCommit(() -> registry.deactivate(userId));
    }

    /** Deletes every recorded exchange of the member; the mode itself is left as is. */
    @Transactional
    public void clear(UUID userId) {
        exchanges.deleteByUserId(userId);
    }

    /** Applies the registry change once the DB change is committed, so a rollback never leaves them disagreeing. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    public Optional<MemberDebugSession> state(UUID userId) {
        return sessions.findById(userId);
    }

    /** The sessions currently active among {@code userIds}, by user. */
    public Map<UUID, MemberDebugSession> activeStates(Collection<UUID> userIds) {
        Map<UUID, MemberDebugSession> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        sessions.findByUserIdInAndActiveTrue(userIds).forEach(s -> result.put(s.getUserId(), s));
        return result;
    }

    /** The member's debug mode as the admin sees it; empty when it was never switched on. */
    public Optional<MemberDebugStateDto> stateDto(UUID userId) {
        return sessions.findById(userId).map(this::toDto);
    }

    /** {@link #activeStates} as DTOs. */
    public Map<UUID, MemberDebugStateDto> activeStateDtos(Collection<UUID> userIds) {
        Map<UUID, MemberDebugStateDto> result = new HashMap<>();
        activeStates(userIds).forEach((id, session) -> result.put(id, toDto(session)));
        return result;
    }

    private MemberDebugStateDto toDto(MemberDebugSession s) {
        Instant purgeAt = s.getDisabledAt() == null ? null : s.getDisabledAt().plus(RETENTION_AFTER_DISABLE);
        return new MemberDebugStateDto(s.isActive(), s.isRevealSecrets(), s.getEnabledAt(), s.getEnabledBy(),
                s.getDisabledAt(), purgeAt, exchanges.countByUserId(s.getUserId()));
    }
}
