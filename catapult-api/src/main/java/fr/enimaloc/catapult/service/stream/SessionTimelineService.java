package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.repository.stream.StreamCategorySegmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the timeline of each member's live: the periods spent on a (Twitch category, detected game) pair, so a raid
 * can tell what the session was made of. Only an ongoing {@link StreamSession} gets periods; at most one period per
 * member is running, and a new pair closes it at the same instant.
 */
@Service
public class SessionTimelineService {

    private final StreamCategorySegmentRepository repository;
    private final StreamSessionService sessions;
    private final Clock clock;

    @Autowired
    public SessionTimelineService(StreamCategorySegmentRepository repository, StreamSessionService sessions) {
        this(repository, sessions, Clock.systemUTC());
    }

    public SessionTimelineService(StreamCategorySegmentRepository repository, StreamSessionService sessions,
                                  Clock clock) {
        this.repository = repository;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Records that the member's live is on this pair at {@code at}. The running period is kept when the pair is the
     * same (the category and the game compared as they are), and a period whose category was unknown takes the
     * observed one without being split; otherwise it is closed at {@code at} and a new one opens. Nothing happens
     * outside an ongoing session.
     */
    @Transactional
    public void observe(UserAccount user, String categoryId, String categoryName, String gameName, Instant at) {
        Optional<StreamSession> session = ongoingSession(user);
        if (session.isEmpty()) {
            return;
        }
        String streamId = session.get().getStreamId();
        Optional<StreamCategorySegment> running = closeOthersAndFindRunning(user, streamId, at);
        if (running.isPresent()) {
            StreamCategorySegment segment = running.get();
            boolean sameGame = Objects.equals(segment.getGameName(), gameName);
            if (sameGame && Objects.equals(segment.getCategoryId(), categoryId)) {
                return;
            }
            if (sameGame && segment.getCategoryId() == null) {
                segment.setCategoryId(categoryId);
                segment.setCategoryName(categoryName);
                repository.save(segment);
                return;
            }
            close(segment, at);
        }
        StreamCategorySegment opened = new StreamCategorySegment();
        opened.setUserId(user.getId());
        opened.setStreamId(streamId);
        opened.setCategoryId(categoryId);
        opened.setCategoryName(categoryName);
        opened.setGameName(gameName);
        opened.setStartedAt(at);
        repository.save(opened);
    }

    /** Records a category change, keeping the game of the running period (none without one). */
    @Transactional
    public void observeCategory(UserAccount user, String categoryId, String categoryName, Instant at) {
        String gameName = runningOfCurrentStream(user).map(StreamCategorySegment::getGameName).orElse(null);
        observe(user, categoryId, categoryName, gameName, at);
    }

    /** Records a detected game change ({@code null}: no game), keeping the category of the running period. */
    @Transactional
    public void observeGame(UserAccount user, String gameName, Instant at) {
        Optional<StreamCategorySegment> running = runningOfCurrentStream(user);
        observe(user,
                running.map(StreamCategorySegment::getCategoryId).orElse(null),
                running.map(StreamCategorySegment::getCategoryName).orElse(null),
                gameName, at);
    }

    /** Closes the member's running period at {@code at}, whatever stream it belongs to. */
    @Transactional
    public void closeOpen(UserAccount user, Instant at) {
        repository.findByUserIdAndEndedAtIsNull(user.getId()).forEach(segment -> close(segment, at));
    }

    /** The periods of the member's current (or last) stream, oldest first; empty without any stream. */
    @Transactional(readOnly = true)
    public List<StreamCategorySegment> currentSession(UserAccount user) {
        return sessions.find(user)
                .map(session -> repository.findByUserIdAndStreamIdOrderByStartedAtAsc(user.getId(),
                        session.getStreamId()))
                .orElse(List.of());
    }

    /**
     * Inserts a closed period of {@code duration} that ends just where the running period starts (at the session start
     * when it has no period, after its last closed one when none runs), so periods added one after the other read in
     * the order they were added. Every closed period before it moves back by {@code duration}, and the session start
     * as much: a long session is built without waiting (mock panel). Nothing happens outside an ongoing session.
     */
    @Transactional
    public void addPast(UserAccount user, String categoryId, String categoryName, String gameName,
                        Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("A past period needs a positive duration, got " + duration);
        }
        Optional<StreamSession> session = ongoingSession(user);
        if (session.isEmpty()) {
            return;
        }
        List<StreamCategorySegment> segments = currentSession(user);
        Instant end = segments.stream()
                .filter(segment -> segment.getEndedAt() == null)
                .map(StreamCategorySegment::getStartedAt)
                .max(Comparator.naturalOrder())
                .or(() -> segments.stream().map(StreamCategorySegment::getEndedAt).max(Comparator.naturalOrder()))
                .orElse(session.get().getStartedAt());
        segments.stream()
                .filter(segment -> segment.getEndedAt() != null && !segment.getEndedAt().isAfter(end))
                .forEach(segment -> {
                    segment.setStartedAt(segment.getStartedAt().minus(duration));
                    segment.setEndedAt(segment.getEndedAt().minus(duration));
                    repository.save(segment);
                });
        StreamCategorySegment past = new StreamCategorySegment();
        past.setUserId(user.getId());
        past.setStreamId(session.get().getStreamId());
        past.setCategoryId(categoryId);
        past.setCategoryName(categoryName);
        past.setGameName(gameName);
        past.setStartedAt(end.minus(duration));
        past.setEndedAt(end);
        repository.save(past);
        sessions.moveStartBack(user, duration);
    }

    private Optional<StreamSession> ongoingSession(UserAccount user) {
        return sessions.find(user).filter(StreamSession::isOngoing);
    }

    private Optional<StreamCategorySegment> runningOfCurrentStream(UserAccount user) {
        return ongoingSession(user).flatMap(session -> repository.findByUserIdAndEndedAtIsNull(user.getId()).stream()
                .filter(segment -> segment.getStreamId().equals(session.getStreamId()))
                .max(Comparator.comparing(StreamCategorySegment::getStartedAt)));
    }

    /**
     * Returns the latest running period of {@code streamId} and closes every other running period of the member at
     * {@code at}: one left over from a stream that ended unseen, or a duplicate the database could not refuse (H2).
     */
    private Optional<StreamCategorySegment> closeOthersAndFindRunning(UserAccount user, String streamId, Instant at) {
        List<StreamCategorySegment> running = repository.findByUserIdAndEndedAtIsNull(user.getId()).stream()
                .sorted(Comparator.comparing(StreamCategorySegment::getStartedAt).reversed())
                .toList();
        StreamCategorySegment kept = running.stream()
                .filter(segment -> segment.getStreamId().equals(streamId))
                .findFirst()
                .orElse(null);
        running.stream().filter(segment -> segment != kept).forEach(segment -> close(segment, at));
        return Optional.ofNullable(kept);
    }

    /**
     * Ends a period, never before its start. Flushed right away: Hibernate runs inserts before updates, and the
     * period opened next must not meet this one still running in the partial unique index.
     */
    private void close(StreamCategorySegment segment, Instant at) {
        segment.setEndedAt(at.isBefore(segment.getStartedAt()) ? segment.getStartedAt() : at);
        repository.saveAndFlush(segment);
    }
}
