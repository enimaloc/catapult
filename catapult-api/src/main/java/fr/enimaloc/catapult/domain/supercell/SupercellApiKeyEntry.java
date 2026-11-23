package fr.enimaloc.catapult.domain.supercell;

import fr.enimaloc.catapult.domain.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A shared Supercell API key (a JWT, bound to the IP addresses it was created for) and the game whose API it opens
 * ({@code clash-of-clans}, {@code clash-royale} or {@code brawl-stars}).
 */
@Entity
@Table(name = "supercell_api_key")
@Getter
@Setter
@NoArgsConstructor
public class SupercellApiKeyEntry {

    @Id
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "game", nullable = false)
    private String game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner", nullable = true)
    private UserAccount owner;

    @Column(name = "is_exclusive", nullable = false)
    private boolean exclusive = false;

    public SupercellApiKeyEntry(String apiKey, String game) {
        this.apiKey = apiKey;
        this.game = game;
    }
}
