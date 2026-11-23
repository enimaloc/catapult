package fr.enimaloc.catapult.domain.blizzard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's Blizzard account: the region of their Battle.net account and, once their World of Warcraft characters
 * were imported, which of them is their main ({@code wowMainRegion} + {@code wowMainCharacterId}; both null when
 * none). The row exists only while Battle.net is linked. No secret is held.
 */
@Entity
@Table(name = "blizzard_account")
@Getter
@Setter
public class BlizzardAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** One of {@code eu|us|kr|tw}. */
    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "wow_main_region")
    private String wowMainRegion;

    @Column(name = "wow_main_character_id")
    private Long wowMainCharacterId;

    /** When the characters were last imported; null while they were not (or were deleted). */
    @Column(name = "wow_imported_at")
    private Instant wowImportedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; null until first saved, so that a new row is persisted rather than merged. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public BlizzardAccount() {
    }

    public BlizzardAccount(UUID userId) {
        this.userId = userId;
    }
}
