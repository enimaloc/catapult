package fr.enimaloc.catapult.domain.bungie;

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
 * A member's Bungie.net account, linked through Bungie's OAuth: the Bungie.net membership ({@code membershipId}), the
 * Destiny 2 membership the stats are read from ({@code destinyMembershipId} + {@code membershipType}; null when the
 * account has no Destiny 2 profile) and the refresh token (encrypted, rotated on every refresh). {@code invalidSince}
 * is set once Bungie refused the refresh token. The access token is never stored.
 */
@Entity
@Table(name = "bungie_account")
@Getter
@Setter
public class BungieAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "membership_id", nullable = false)
    private String membershipId;

    /** The Bungie Name ({@code Name#1234}) to display; null when Bungie gave none. */
    @Column(name = "display_name")
    private String displayName;

    @Column(name = "destiny_membership_id")
    private String destinyMembershipId;

    @Column(name = "membership_type")
    private Integer membershipType;

    /** Encrypted; null once refused. */
    @Column(name = "refresh_token_enc")
    private String refreshTokenEnc;

    @Column(name = "refresh_expires_at")
    private Instant refreshExpiresAt;

    @Column(name = "invalid_since")
    private Instant invalidSince;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; null until first saved, so that a new row is persisted rather than merged. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public BungieAccount() {
    }

    public BungieAccount(UUID userId) {
        this.userId = userId;
    }

    /** Whether the link can still be read with: a refresh token Bungie has not refused. */
    public boolean usable() {
        return refreshTokenEnc != null && invalidSince == null;
    }

    @Override
    public String toString() {
        // Never the refresh token, even encrypted
        return "BungieAccount[userId=" + userId + ", membershipId=" + membershipId + "]";
    }
}
