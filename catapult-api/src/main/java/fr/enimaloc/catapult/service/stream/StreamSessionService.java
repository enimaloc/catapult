package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.repository.stream.StreamSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Remembers the last stream handled for each member. It lives in the database so it travels with the replication
 * between sites, letting a site that takes over tell which stream starts and ends it missed.
 */
@Service
@RequiredArgsConstructor
public class StreamSessionService {

    private final StreamSessionRepository repository;

    @Transactional(readOnly = true)
    public Optional<StreamSession> find(UserAccount user) {
        return repository.findById(user.getId());
    }

    /** Records a stream as started and not yet ended, replacing whatever was remembered before. */
    @Transactional
    public void recordStart(UserAccount user, String streamId, Instant startedAt) {
        StreamSession session = repository.findById(user.getId()).orElseGet(() -> {
            StreamSession created = new StreamSession();
            created.setUserId(user.getId());
            return created;
        });
        session.setStreamId(streamId);
        session.setStartedAt(startedAt);
        session.setEndedAt(null);
        repository.save(session);
    }

    /** Marks the remembered stream as ended; nothing happens when no stream was remembered. */
    @Transactional
    public void recordEnd(UserAccount user, Instant endedAt) {
        repository.findById(user.getId()).ifPresent(session -> {
            session.setEndedAt(endedAt);
            repository.save(session);
        });
    }
}
