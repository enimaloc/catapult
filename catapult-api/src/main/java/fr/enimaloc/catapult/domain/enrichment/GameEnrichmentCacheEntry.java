package fr.enimaloc.catapult.domain.enrichment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** One cached answer of an enrichment source; a null {@code payload} means the source has nothing for the key. */
@Entity
@Table(name = "game_enrichment_cache")
@IdClass(GameEnrichmentCacheKey.class)
@Getter
@Setter
@NoArgsConstructor
public class GameEnrichmentCacheEntry {

    /** {@code EnrichmentSource#key()}. */
    @Id
    @Column(length = 32)
    private String source;

    /** Source-specific key, e.g. "steam:<appid>" or "name:<normalized_name>". */
    @Id
    @Column(name = "lookup_key", length = 512)
    private String lookupKey;

    /** The source's answer as raw JSON, null when known absent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public GameEnrichmentCacheEntry(String source, String lookupKey, String payload, Instant fetchedAt) {
        this.source    = source;
        this.lookupKey = lookupKey;
        this.payload   = payload;
        this.fetchedAt = fetchedAt;
    }
}
