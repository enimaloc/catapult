package fr.enimaloc.catapult.domain.hypixel;

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
 * A member's declared Minecraft account for Hypixel: the UUID Hypixel knows them by (32 hex digits, no dashes; stable
 * when the name changes) and the name it was shown with when declared. No secret.
 */
@Entity
@Table(name = "hypixel_account")
@Getter
@Setter
@ToString
public class HypixelAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "minecraft_uuid", nullable = false)
    private String minecraftUuid;

    @Column(name = "minecraft_name", nullable = false)
    private String minecraftName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; null until first saved, so that a new row is persisted rather than merged. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public HypixelAccount() {
    }

    public HypixelAccount(UUID userId) {
        this.userId = userId;
    }
}
