package fr.enimaloc.catapult.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A member's personal API token, as its SHA-256 (hex) only: the token itself is never stored. */
@Entity
@Table(name = "user_api_token")
@Getter
@Setter
@NoArgsConstructor
public class UserApiToken {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserApiToken(UUID userId, String tokenHash, Instant createdAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
    }
}
