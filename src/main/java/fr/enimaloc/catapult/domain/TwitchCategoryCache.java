package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "twitch_category_cache")
@Getter
@Setter
@NoArgsConstructor
public class TwitchCategoryCache {

    @Id
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "box_art_url", length = 512)
    private String boxArtUrl;

    @Column(name = "igdb_id")
    private String igdbId;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    public TwitchCategoryCache(String id, String name, String boxArtUrl) {
        this.id        = id;
        this.name      = name;
        this.boxArtUrl = boxArtUrl;
        this.cachedAt  = Instant.now();
    }
}
