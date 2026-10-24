package fr.enimaloc.catapult.domain.binding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An admin's Twitch category for a detected game (platform + its ID), for every streamer: it wins over the IGDB
 * resolution, never over a streamer's MANUAL choice. The bindings that follow it have the MAPPED status.
 */
@Entity
@Table(name = "game_mapping")
@Getter
@Setter
public class GameMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private GameBinding.SourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "twitch_game_id", nullable = false)
    private String twitchGameId;

    @Column(name = "twitch_game_name", nullable = false)
    private String twitchGameName;

    /** false: the bindings keep IGDB's CCLs; true: they get {@link #ccls}, even when empty. */
    @Column(name = "ccls_overridden", nullable = false)
    private boolean cclsOverridden = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "game_mapping_ccl",
        joinColumns = @JoinColumn(name = "mapping_id")
    )
    @Column(name = "ccl")
    private Set<String> ccls = new HashSet<>();

    /** Same as {@link #cclsOverridden}, for the TWs; a streamer's own TWs (twOverride) still win. */
    @Column(name = "tws_overridden", nullable = false)
    private boolean twsOverridden = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "game_mapping_tw",
        joinColumns = @JoinColumn(name = "mapping_id")
    )
    @Column(name = "tw")
    private Set<String> tws = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The admin who last saved it. */
    @Column(name = "updated_by")
    private UUID updatedBy;
}
