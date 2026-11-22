package fr.enimaloc.catapult.domain.riot;

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
 * A shared Riot API key. {@code regionScope} keeps it to one routing cluster ({@code americas}, {@code asia},
 * {@code europe}, {@code sea}); null serves every cluster.
 */
@Entity
@Table(name = "riot_api_key")
@Getter
@Setter
@NoArgsConstructor
public class RiotApiKeyEntry {

    @Id
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "region_scope")
    private String regionScope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner", nullable = true)
    private UserAccount owner;

    @Column(name = "is_exclusive", nullable = false)
    private boolean exclusive = false;

    public RiotApiKeyEntry(String apiKey, String regionScope) {
        this.apiKey = apiKey;
        this.regionScope = regionScope;
    }
}
