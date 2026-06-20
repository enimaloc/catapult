package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "dtdd_game_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DtddGameCache {

    @Id
    @Column(name = "dtdd_id", nullable = false)
    private Long dtddId;

    @Column(name = "name", nullable = false, length = 512)
    private String name;

    @Column(name = "slug", length = 512)
    private String slug;

    @Column(name = "media_type", length = 64)
    private String mediaType;

    @Column(name = "poster_url", length = 1024)
    private String posterUrl;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
