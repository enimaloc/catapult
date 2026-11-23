package fr.enimaloc.catapult.domain.osu;

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
 * A member's osu! account, linked through osu!'s OAuth: the osu! user id, the username and default ruleset seen at the
 * last link, and the refresh token (encrypted, replaced on every refresh). {@code invalidSince} is set once osu!
 * refused the refresh token. The access token is never stored.
 */
@Entity
@Table(name = "osu_account")
@Getter
@Setter
public class OsuAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "osu_user_id", nullable = false)
    private Long osuUserId;

    /** The osu! username to display; null when osu! gave none. */
    @Column(name = "username")
    private String username;

    /** The member's default ruleset on osu! ({@code osu}, {@code taiko}, {@code fruits}, {@code mania}). */
    @Column(name = "playmode")
    private String playmode;

    /** Encrypted; null once refused. */
    @Column(name = "refresh_token_enc")
    private String refreshTokenEnc;

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

    public OsuAccount() {
    }

    public OsuAccount(UUID userId) {
        this.userId = userId;
    }

    /** Whether the link can still be read with: a refresh token osu! has not refused. */
    public boolean usable() {
        return refreshTokenEnc != null && invalidSince == null;
    }

    @Override
    public String toString() {
        // Never the refresh token, even encrypted
        return "OsuAccount[userId=" + userId + ", osuUserId=" + osuUserId + "]";
    }
}
