package fr.enimaloc.catapult.domain.itad;

import fr.enimaloc.catapult.domain.account.UserAccount;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "itad_api_key")
@Getter
@Setter
@NoArgsConstructor
public class ItadApiKeyEntry {

    @Id
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner", nullable = true)
    private UserAccount owner;

    @Column(name = "is_exclusive", nullable = false)
    private boolean exclusive = false;

    public ItadApiKeyEntry(String apiKey) {
        this.apiKey = apiKey;
    }
}
