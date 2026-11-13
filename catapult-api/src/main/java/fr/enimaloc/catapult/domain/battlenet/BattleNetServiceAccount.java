package fr.enimaloc.catapult.domain.battlenet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "battlenet_service_account")
@Getter
@Setter
@ToString
public class BattleNetServiceAccount {

    public enum AuthState {
        NEW, CODE_REQUIRED, READY, AUTH_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String label;

    @ToString.Exclude
    @Column(nullable = false)
    private String email;

    /** Battle.net password, encrypted through TokenEncryptionService. */
    @ToString.Exclude
    @Column(nullable = false, columnDefinition = "text")
    private String passwordEnc;

    /** Playwright storageState (cookies), encrypted through TokenEncryptionService. */
    @ToString.Exclude
    @Column(columnDefinition = "text")
    private String storageStateEnc;

    /** Filled from OnLogonComplete; null before the first login. */
    private String battleTag;

    /** BGS account id (equals the OAuth userinfo id); null before the first login. */
    @Column(unique = true)
    private Long bnetAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthState authState = AuthState.NEW;

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

    /** Optimistic lock: admin edits and in-flight logins both write the account. */
    @Version
    private long version;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
