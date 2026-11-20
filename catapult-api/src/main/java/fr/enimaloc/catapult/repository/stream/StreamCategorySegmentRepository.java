package fr.enimaloc.catapult.repository.stream;

import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StreamCategorySegmentRepository extends JpaRepository<StreamCategorySegment, UUID> {

    /** The running periods of a member; the V121 partial index keeps it to one at most in Postgres. */
    List<StreamCategorySegment> findByUserIdAndEndedAtIsNull(UUID userId);

    List<StreamCategorySegment> findByUserIdAndStreamIdOrderByStartedAtAsc(UUID userId, String streamId);
}
