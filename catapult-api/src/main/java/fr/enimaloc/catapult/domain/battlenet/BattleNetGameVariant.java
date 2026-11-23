package fr.enimaloc.catapult.domain.battlenet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An admin's rule turning a Battle.net game into a variant of its own: while a friend plays {@link #fourcc} with an
 * activity text matching one of {@link #patterns}, the game detected is {@link #sourceId()}, named
 * {@link #displayName}, with its own bindings and game mapping.
 */
@Entity
@Table(name = "battlenet_game_variant")
@Getter
@Setter
public class BattleNetGameVariant {

    /** How a pattern is compared with the activity text, ignoring case. */
    public enum MatchType {
        /** The text starts with the pattern. */
        PREFIX,
        /** The text contains the pattern. */
        CONTAINS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String fourcc;

    /** Part of the source ID of the variant's bindings: never changed once created. */
    @Column(name = "variant_key", nullable = false, updatable = false)
    private String variantKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false)
    private MatchType matchType;

    /** Compared as typed: a trailing space is part of the pattern. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "patterns_json", nullable = false, columnDefinition = "jsonb")
    private List<String> patterns = new ArrayList<>();

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The admin who last saved it. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** The source ID of the games detected through this variant, e.g. "WoW:Forever". */
    public String sourceId() {
        return fourcc + ":" + variantKey;
    }
}
