package fr.enimaloc.catapult.domain.epic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "epic_service_account")
@Getter
@Setter
@ToString
public class EpicServiceAccount {

    public enum AuthState {
        READY, REAUTH_REQUIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** OAuth refresh token, encrypted through TokenEncryptionService. */
    @ToString.Exclude
    @Column(nullable = false, columnDefinition = "text")
    private String refreshTokenEnc;

    /** When the refresh token stops being usable. */
    @Column(nullable = false)
    private Instant refreshExpiresAt;

    @Column(nullable = false, unique = true)
    private String epicAccountId;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private Instant lastRefreshAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthState authState = AuthState.READY;

    @Column(nullable = false)
    private int fillOrder;

    @Column(nullable = false)
    private boolean friendLimitReached;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /** Optimistic lock: admin edits and token refreshes both write the account. */
    @Version
    private long version;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
