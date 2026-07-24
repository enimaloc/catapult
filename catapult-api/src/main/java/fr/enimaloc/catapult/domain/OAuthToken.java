package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "oauth_token",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider"})
)
@Getter
@Setter
public class OAuthToken {

    public enum Provider {
        TWITCH, STEAM, SYSTEM, XBOX
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Space-separated, mirroring how Twitch/OAuth2 report scopes (OAuth2AccessToken#getScopes()). */
    @Column(name = "granted_scopes", columnDefinition = "TEXT")
    private String grantedScopes;
}
