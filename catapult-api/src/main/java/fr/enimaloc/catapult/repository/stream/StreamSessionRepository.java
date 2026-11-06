package fr.enimaloc.catapult.repository.stream;

import fr.enimaloc.catapult.domain.stream.StreamSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StreamSessionRepository extends JpaRepository<StreamSession, UUID> {
}
