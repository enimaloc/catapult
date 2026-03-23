package fr.esportline.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "igdb_game_cache")
@Getter
@Setter
@NoArgsConstructor
public class IgdbGameCacheEntry {

    /** "steam:<uid>" ou "name:<normalized_name>" */
    @Id
    @Column(name = "lookup_key")
    private String lookupKey;

    @Column(name = "igdb_id", nullable = false)
    private String igdbId;

    @Column(nullable = false)
    private String name;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    public IgdbGameCacheEntry(String lookupKey, String igdbId, String name) {
        this.lookupKey = lookupKey;
        this.igdbId    = igdbId;
        this.name      = name;
        this.cachedAt  = Instant.now();
    }
}
