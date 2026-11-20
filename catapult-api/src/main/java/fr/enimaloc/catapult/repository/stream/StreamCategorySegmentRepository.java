package fr.enimaloc.catapult.repository.stream;

import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StreamCategorySegmentRepository extends JpaRepository<StreamCategorySegment, UUID> {

    /** The running periods of a member; the V121 partial index keeps it to one at most in Postgres. */
    List<StreamCategorySegment> findByUserIdAndEndedAtIsNull(UUID userId);

    List<StreamCategorySegment> findByUserIdAndStreamIdOrderByStartedAtAsc(UUID userId, String streamId);

    /**
     * Deletes the periods of the live a member's session record ended before the cutoff, and the closed periods of
     * any earlier live ended before it. A running period of the current live is never touched.
     */
    @Modifying
    @Query("""
            delete from StreamCategorySegment s where exists (
                select 1 from StreamSession ss where ss.userId = s.userId and (
                    (ss.streamId = s.streamId and ss.endedAt is not null and ss.endedAt < :cutoff)
                    or (ss.streamId <> s.streamId and s.endedAt is not null and s.endedAt < :cutoff)))
            """)
    int deleteEndedBefore(@Param("cutoff") Instant cutoff);
}
