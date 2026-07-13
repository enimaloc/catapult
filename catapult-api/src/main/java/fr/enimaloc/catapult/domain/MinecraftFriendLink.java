package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "minecraft_friend_link")
@Getter
@Setter
public class MinecraftFriendLink {

    public enum Status {
        PENDING, ACCEPTED, REMOVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_account_id", nullable = false)
    private MinecraftServiceAccount serviceAccount;

    /** UUID Mojang (format avec tirets). */
    @Column(nullable = false)
    private String minecraftProfileId;

    @Column(nullable = false)
    private String minecraftName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Instant acceptedAt;
}
