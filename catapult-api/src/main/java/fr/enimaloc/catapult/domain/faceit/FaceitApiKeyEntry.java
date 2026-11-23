package fr.enimaloc.catapult.domain.faceit;

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

/** A shared FACEIT Data API server key. */
@Entity
@Table(name = "faceit_api_key")
@Getter
@Setter
@NoArgsConstructor
public class FaceitApiKeyEntry {

    @Id
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner", nullable = true)
    private UserAccount owner;

    @Column(name = "is_exclusive", nullable = false)
    private boolean exclusive = false;

    public FaceitApiKeyEntry(String apiKey) {
        this.apiKey = apiKey;
    }
}
