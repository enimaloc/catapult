package fr.enimaloc.catapult.domain.ankama;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ankama_service_account")
@Getter
@Setter
@ToString
public class AnkamaServiceAccount {

    public enum AuthState {
        CODE_REQUIRED, READY, REAUTH_REQUIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** HAAPI key, encrypted through TokenEncryptionService. */
    @ToString.Exclude
    @Column(nullable = false, columnDefinition = "text")
    private String apiKeyEnc;

    /** OAuth refresh token, encrypted through TokenEncryptionService. */
    @ToString.Exclude
    @Column(nullable = false, columnDefinition = "text")
    private String refreshTokenEnc;

    @Column(nullable = false, unique = true)
    private long ankamaAccountId;

    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false)
    private String tag;

    @ToString.Exclude
    @Column(nullable = false)
    private String deviceId;

    @ToString.Exclude
    @Column(nullable = false)
    private String hm1;

    private Long certificateId;

    /** Shield certificate, encrypted through TokenEncryptionService; null until one is registered. */
    @ToString.Exclude
    @Column(columnDefinition = "text")
    private String certificateEnc;

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

    public String displayTag() {
        return nickname + "#" + tag;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
