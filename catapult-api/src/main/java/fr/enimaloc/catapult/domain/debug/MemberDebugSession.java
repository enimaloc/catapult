package fr.enimaloc.catapult.domain.debug;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** The debug mode of one member, set by an admin. While {@link #active}, the member's HTTP exchanges are recorded. */
@Entity
@Table(name = "member_debug_session")
@Getter
@Setter
public class MemberDebugSession {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private boolean active;

    /** false: secrets (the member's and the application's) are masked in what is recorded. */
    @Column(name = "reveal_secrets", nullable = false)
    private boolean revealSecrets;

    @Column(name = "enabled_by")
    private UUID enabledBy;

    @Column(name = "enabled_at", nullable = false)
    private Instant enabledAt;

    /** Set when the mode is switched off, null while active; the exchanges are purged 30 days after it. */
    @Column(name = "disabled_at")
    private Instant disabledAt;
}
