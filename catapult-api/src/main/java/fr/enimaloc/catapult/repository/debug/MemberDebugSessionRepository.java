package fr.enimaloc.catapult.repository.debug;

import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MemberDebugSessionRepository extends JpaRepository<MemberDebugSession, UUID> {

    List<MemberDebugSession> findByActiveTrue();

    List<MemberDebugSession> findByUserIdInAndActiveTrue(Collection<UUID> userIds);

    List<MemberDebugSession> findByActiveFalseAndDisabledAtBefore(Instant cutoff);
}
