package fr.enimaloc.catapult.domain.binding;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** A scheduled occurrence the streamer stopped by hand, so it is not activated again. */
@Entity
@Table(name = "custom_binding_schedule_refusal")
@IdClass(CustomBindingScheduleRefusal.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class CustomBindingScheduleRefusal {

    @Id
    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Id
    @Column(name = "occurrence_start")
    private Instant occurrenceStart;

    /** Kept to purge the refusal once the occurrence is over. */
    @Column(name = "occurrence_end", nullable = false)
    private Instant occurrenceEnd;

    public CustomBindingScheduleRefusal(UUID scheduleId, Instant occurrenceStart, Instant occurrenceEnd) {
        this.scheduleId = scheduleId;
        this.occurrenceStart = occurrenceStart;
        this.occurrenceEnd = occurrenceEnd;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID scheduleId;
        private Instant occurrenceStart;
    }
}
