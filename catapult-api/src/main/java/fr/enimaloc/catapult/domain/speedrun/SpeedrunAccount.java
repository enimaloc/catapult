package fr.enimaloc.catapult.domain.speedrun;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's speedrun.com account: the name they declared and their personal API key, encrypted. The row exists only
 * while it holds one of them. The key's account ({@code keyUserId}, {@code keyUserName}) is the one speedrun.com
 * answered for the key when it was last checked; {@code keyInvalid} is set when speedrun.com refused it since.
 */
@Entity
@Table(name = "speedrun_account")
@Getter
@Setter
@ToString(exclude = "apiKeyEnc")
public class SpeedrunAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "declared_username")
    private String declaredUsername;

    /** The personal API key, encrypted by {@code TokenEncryptionService}; never sent anywhere but speedrun.com. */
    @Column(name = "api_key_enc")
    private String apiKeyEnc;

    @Column(name = "key_user_id")
    private String keyUserId;

    @Column(name = "key_user_name")
    private String keyUserName;

    @Column(name = "key_checked_at")
    private Instant keyCheckedAt;

    @Column(name = "key_invalid", nullable = false)
    private boolean keyInvalid;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SpeedrunAccount() {
    }

    public SpeedrunAccount(UUID userId) {
        this.userId = userId;
    }

    /** Neither a name nor a key: nothing left to keep. */
    public boolean isEmpty() {
        return declaredUsername == null && apiKeyEnc == null;
    }
}
