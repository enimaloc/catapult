package fr.enimaloc.catapult.domain.hypixel;

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

/** A shared Hypixel API key (a UUID, only ever sent in the {@code API-Key} header). */
@Entity
@Table(name = "hypixel_api_key")
@Getter
@Setter
@NoArgsConstructor
public class HypixelApiKeyEntry {

    @Id
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner", nullable = true)
    private UserAccount owner;

    @Column(name = "is_exclusive", nullable = false)
    private boolean exclusive = false;

    public HypixelApiKeyEntry(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Never the key itself. */
    @Override
    public String toString() {
        return "HypixelApiKeyEntry[exclusive=" + exclusive + "]";
    }
}
