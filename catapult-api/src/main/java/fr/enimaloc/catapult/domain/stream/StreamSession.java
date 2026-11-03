package fr.enimaloc.catapult.domain.stream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** The last stream handled for an account, see V97. */
@Entity
@Table(name = "stream_session")
@Getter
@Setter
@NoArgsConstructor
public class StreamSession {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "stream_id", nullable = false, length = 64)
    private String streamId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public boolean isOngoing() {
        return endedAt == null;
    }
}
