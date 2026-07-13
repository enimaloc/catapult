package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "minecraft_service_account")
@Getter
@Setter
public class MinecraftServiceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String minecraftUsername;

    /** Refresh token MSA, chiffré via TokenEncryptionService. */
    @Column(nullable = false, columnDefinition = "text")
    private String msaRefreshToken;

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

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
