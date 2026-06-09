package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_account")
@Getter
@Setter
public class UserAccount {

    public enum Status {
        ACTIVE, INACTIVE, PENDING_DELETION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "twitch_id", unique = true)
    private String twitchId;

    @Column(name = "twitch_username")
    private String twitchUsername;

    @Column(name = "profile_image_url", length = 512)
    private String profileImageUrl;

    @Column(name = "steam_id", unique = true)
    private String steamId;

    @Column(name = "steam_personal_token")
    private String steamPersonalToken;

    @Column(name = "steam_token_shared", nullable = false)
    private boolean steamTokenShared = false;

    @Column(name = "is_system", nullable = false)
    private boolean systemAccount = false;

    @Column(name = "bot_enabled", nullable = false)
    private boolean botEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
