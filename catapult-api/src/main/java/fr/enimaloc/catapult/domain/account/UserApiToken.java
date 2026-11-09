package fr.enimaloc.catapult.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A member's personal API token of one {@link ApiTokenScope} (at most one per scope), as its SHA-256 (hex) only: the token itself is never stored. */
@Entity
@Table(name = "user_api_token")
@Getter
@Setter
@NoArgsConstructor
@IdClass(UserApiToken.Key.class)
public class UserApiToken {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 8)
    private ApiTokenScope scope;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserApiToken(UUID userId, ApiTokenScope scope, String tokenHash, Instant createdAt) {
        this.userId = userId;
        this.scope = scope;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
    }

    /** The composite primary key: the member and the scope. */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements java.io.Serializable {
        private UUID userId;
        private ApiTokenScope scope;
    }
}
