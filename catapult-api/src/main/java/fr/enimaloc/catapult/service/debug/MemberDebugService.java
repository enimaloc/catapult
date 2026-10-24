package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.repository.debug.MemberDebugSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Admin-driven switch of a member's debug mode, and the clearing of what was recorded. */
@Service
public class MemberDebugService {

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
        registry.activate(userId, revealSecrets);
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
        registry.deactivate(userId);
    }

    /** Deletes every recorded exchange of the member; the mode itself is left as is. */
    @Transactional
    public void clear(UUID userId) {
        exchanges.deleteByUserId(userId);
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
}
