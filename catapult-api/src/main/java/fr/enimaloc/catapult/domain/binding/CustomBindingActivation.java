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

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "custom_binding_id", nullable = false)
    private CustomBinding customBinding;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    public CustomBindingActivation(UUID userId, CustomBinding customBinding, Instant activatedAt) {
        this.userId = userId;
        this.customBinding = customBinding;
        this.activatedAt = activatedAt;
    }
}
