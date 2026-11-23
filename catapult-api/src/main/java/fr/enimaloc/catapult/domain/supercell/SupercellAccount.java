package fr.enimaloc.catapult.domain.supercell;

import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
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
 * A member's declared Supercell player tags, one per game ({@code #2PP}…, as Supercell spells it), each with the
 * player name Supercell gave for it. A game without a tag has both null; a row without any tag is deleted. No secret.
 */
@Entity
@Table(name = "supercell_account")
@Getter
@Setter
@ToString
public class SupercellAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "coc_tag")
    private String cocTag;

    @Column(name = "coc_name")
    private String cocName;

    @Column(name = "cr_tag")
    private String crTag;

    @Column(name = "cr_name")
    private String crName;

    @Column(name = "bs_tag")
    private String bsTag;

    @Column(name = "bs_name")
    private String bsName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; null until first saved, so that a new row is persisted rather than merged. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public SupercellAccount() {
    }

    public SupercellAccount(UUID userId) {
        this.userId = userId;
    }

    /** The tag declared for {@code game}; null for none. */
    public String tag(SupercellGame game) {
        return switch (game) {
            case CLASH_OF_CLANS -> cocTag;
            case CLASH_ROYALE -> crTag;
            case BRAWL_STARS -> bsTag;
        };
    }

    /** The player name Supercell gave for the tag of {@code game}; null for none. */
    public String name(SupercellGame game) {
        return switch (game) {
            case CLASH_OF_CLANS -> cocName;
            case CLASH_ROYALE -> crName;
            case BRAWL_STARS -> bsName;
        };
    }

    /** Declares {@code tag} (named {@code name}) for {@code game}; a null tag forgets the game's. */
    public void set(SupercellGame game, String tag, String name) {
        String n = tag == null ? null : name;
        switch (game) {
            case CLASH_OF_CLANS -> {
                cocTag = tag;
                cocName = n;
            }
            case CLASH_ROYALE -> {
                crTag = tag;
                crName = n;
            }
            case BRAWL_STARS -> {
                bsTag = tag;
                bsName = n;
            }
        }
    }

    /** Whether no game has a tag any more. */
    public boolean empty() {
        return cocTag == null && crTag == null && bsTag == null;
    }
}
