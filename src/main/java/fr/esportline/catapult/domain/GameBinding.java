package fr.esportline.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "game_binding")
@Getter
@Setter
public class GameBinding {

    public enum SourceType {
        STEAM, XBOX, BATTLENET, MANUAL
    }

    public enum Status {
        AUTO, MANUAL, INCOMPLETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "source_id")
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "twitch_game_id")
    private String twitchGameId;

    @Column(name = "twitch_game_name")
    private String twitchGameName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private boolean ignored = false;

    @Column(name = "ccl_enabled", nullable = false)
    private boolean cclEnabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "binding_ccl",
        joinColumns = @JoinColumn(name = "binding_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "ccl")
    private Set<TwitchCcl> ccls = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
