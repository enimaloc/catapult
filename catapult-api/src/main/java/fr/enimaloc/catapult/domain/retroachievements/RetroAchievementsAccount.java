package fr.enimaloc.catapult.domain.retroachievements;

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
 * A member's RetroAchievements account: the name they declared, the ULID RetroAchievements proved for it (stable
 * when the name changes) and, optionally, their personal web API key, encrypted. The row exists only while it holds
 * a name; {@code keyInvalid} is set when RetroAchievements refused the key at its last use.
 */
@Entity
@Table(name = "retroachievements_account")
@Getter
@Setter
@ToString(exclude = "apiKeyEnc")
public class RetroAchievementsAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "user_ulid", nullable = false)
    private String userUlid;

    /** The personal API key, encrypted by {@code TokenEncryptionService}; never sent anywhere but RetroAchievements. */
    @Column(name = "api_key_enc")
    private String apiKeyEnc;

    @Column(name = "key_invalid", nullable = false)
    private boolean keyInvalid;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock: a write made from a stale read fails instead of bringing back what was removed meanwhile.
     * Null until first saved, so that a new row is persisted rather than merged.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public RetroAchievementsAccount() {
    }

    public RetroAchievementsAccount(UUID userId) {
        this.userId = userId;
    }
}
