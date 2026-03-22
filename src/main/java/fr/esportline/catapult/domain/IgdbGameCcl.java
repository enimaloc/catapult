package fr.esportline.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "igdb_game_ccl_cache")
@Getter
@Setter
@NoArgsConstructor
public class IgdbGameCcl {

    @Id
    @Column(name = "igdb_id")
    private String igdbId;

    @Convert(converter = TwitchCclSetConverter.class)
    @Column(nullable = false, length = 500)
    private Set<TwitchCcl> ccls;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    public IgdbGameCcl(String igdbId, Set<TwitchCcl> ccls) {
        this.igdbId    = igdbId;
        this.ccls      = ccls;
        this.cachedAt  = Instant.now();
    }
}
