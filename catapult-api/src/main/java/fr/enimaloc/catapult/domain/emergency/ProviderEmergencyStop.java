package fr.enimaloc.catapult.domain.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A stop of one provider (or all of them, {@code "ALL"}), globally or on a single channel. */
@Entity
@Table(name = "provider_emergency_stop")
@Getter
@Setter
@NoArgsConstructor
public class ProviderEmergencyStop {

    public enum Scope { GLOBAL, CHANNEL }

    public enum Origin { THRESHOLD, CHANNEL_REPORT, OWNER }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** A {@code GetterConfig.Provider} name, or {@code "ALL"}. */
    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_user_id")
    private UserAccount channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Origin origin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lifted_by")
    private String liftedBy;

    public boolean isActive(Instant now) {
        return liftedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
