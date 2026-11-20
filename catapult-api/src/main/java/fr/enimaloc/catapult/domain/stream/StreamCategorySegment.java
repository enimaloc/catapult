package fr.enimaloc.catapult.domain.stream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A period of a member's live spent on one (Twitch category, detected game) pair, see V121. A null category means it
 * was not known yet, a null game that none was detected, a null end that the period is still running.
 */
@Entity
@Table(name = "stream_category_segment")
@Getter
@Setter
@NoArgsConstructor
public class StreamCategorySegment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "stream_id", nullable = false, length = 64)
    private String streamId;

    @Column(name = "category_id", length = 64)
    private String categoryId;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "game_name")
    private String gameName;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;
}
