package fr.enimaloc.catapult.domain.binding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A streamer's active custom binding (at most one), and when it was triggered. */
@Entity
@Table(name = "custom_binding_activation")
@Getter
@Setter
@NoArgsConstructor
public class CustomBindingActivation {

    /** Who switched the binding on: the streamer, or the scheduler on a slot. */
    public enum Origin { MANUAL, SCHEDULED }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "custom_binding_id", nullable = false)
    private CustomBinding customBinding;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Origin origin = Origin.MANUAL;

    /** The slot and occurrence a {@link Origin#SCHEDULED} activation comes from; null once the slot is deleted. */
    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "occurrence_start")
    private Instant occurrenceStart;

    @Column(name = "occurrence_end")
    private Instant occurrenceEnd;

    public CustomBindingActivation(UUID userId, CustomBinding customBinding, Instant activatedAt) {
        this.userId = userId;
        this.customBinding = customBinding;
        this.activatedAt = activatedAt;
    }
}
