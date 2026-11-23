package fr.enimaloc.catapult.domain.faceit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's declared FACEIT nickname (as FACEIT spells it) and the player id FACEIT proved for it (stable when the
 * nickname changes). No secret.
 */
@Entity
@Table(name = "faceit_account")
@Getter
@Setter
@ToString
public class FaceitAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "nickname", nullable = false)
    private String nickname;

    @Column(name = "player_id", nullable = false)
    private String playerId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; null until first saved, so that a new row is persisted rather than merged. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public FaceitAccount() {
    }

    public FaceitAccount(UUID userId) {
        this.userId = userId;
    }
}
