package fr.esportline.catapult.domain;

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
        ACTIVE, PENDING_DELETION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "twitch_id", nullable = false, unique = true)
    private String twitchId;

    @Column(name = "twitch_username", nullable = false)
    private String twitchUsername;

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
