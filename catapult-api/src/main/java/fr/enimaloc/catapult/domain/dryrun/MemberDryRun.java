package fr.enimaloc.catapult.domain.dryrun;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A member in dry run, put there by an admin: the row exists only while the dry run is on. */
@Entity
@Table(name = "member_dry_run")
@Getter
@Setter
public class MemberDryRun {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** The admin who switched it on (kept as is if that account is later deleted: no foreign key). */
    @Column(name = "enabled_by")
    private UUID enabledBy;

    @Column(name = "enabled_at", nullable = false)
    private Instant enabledAt;
}
