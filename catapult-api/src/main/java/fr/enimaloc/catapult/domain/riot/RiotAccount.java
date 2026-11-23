package fr.enimaloc.catapult.domain.riot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's declared Riot ID: the name and tag they gave (as Riot spells them), the PUUID Riot proved for it
 * with the key that resolved it (stable when the Riot ID changes), the League of Legends platform they play on ({@code euw1}…) and its routing
 * cluster ({@code europe}…). No secret.
 */
@Entity
@Table(name = "riot_account")
@Getter
@Setter
@ToString
public class RiotAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "game_name", nullable = false)
    private String gameName;

    @Column(name = "tag_line", nullable = false)
    private String tagLine;

    @Column(name = "puuid", nullable = false)
    private String puuid;

    /**
     * The id ({@code ApiKeyHasher.id}) of the pool key that resolved {@link #puuid}: Riot encrypts its ids per key
     * project, so the PUUID is only valid with that key. Null when unknown (resolved again at first use).
     */
    @Column(name = "puuid_key_id")
    private String puuidKeyId;

    @Column(name = "platform", nullable = false)
    private String platform;

    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; null until first saved, so that a new row is persisted rather than merged. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public RiotAccount() {
    }

    public RiotAccount(UUID userId) {
        this.userId = userId;
    }

    /** The Riot ID as Riot shows it: {@code gameName#tagLine}. */
    public String riotId() {
        return gameName + "#" + tagLine;
    }
}
