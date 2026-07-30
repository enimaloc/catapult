package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriched IGDB game details cache (stale-while-revalidate).
 * Mirrors the {@code igdb_game_details} table defined in V39.
 */
@Entity
@Table(name = "igdb_game_details")
@Getter
@Setter
@NoArgsConstructor
public class IgdbGameDetails {

    @Id
    @Column(name = "igdb_id", length = 64)
    private String igdbId;

    @Column(length = 255)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "first_release_date")
    private Instant firstReleaseDate;

    // AttributeConverter would bind this as varchar; PostgreSQL refuses the
    // implicit varchar→jsonb cast on insert. @JdbcTypeCode(SqlTypes.JSON) lets
    // Hibernate bind it as jsonb natively (same pattern as DtddTopicsCache).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "websites_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> websites = new HashMap<>();

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** IGDB's own user-rating aggregate (0-100), null when IGDB has no rating for this game. */
    @Column
    private Double rating;

    /** IGDB's aggregated critic rating (0-100), same null-means-unset convention as {@link #rating}. */
    @Column(name = "aggregated_rating")
    private Double aggregatedRating;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platforms_json", nullable = false, columnDefinition = "jsonb")
    private List<String> platforms = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dlc_names_json", nullable = false, columnDefinition = "jsonb")
    private List<String> dlcNames = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "similar_game_names_json", nullable = false, columnDefinition = "jsonb")
    private List<String> similarGameNames = new ArrayList<>();
}
