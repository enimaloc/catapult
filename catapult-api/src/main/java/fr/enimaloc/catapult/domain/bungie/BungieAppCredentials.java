package fr.enimaloc.catapult.domain.bungie;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The Bungie.net application Catapult reads Destiny 2 with, set by an admin: the OAuth client id (public), its secret
 * and the API key bound to it, both encrypted ({@code TokenEncryptionService}). One row at most ({@link #ID}).
 */
@Entity
@Table(name = "bungie_app_credentials")
@Getter
@Setter
public class BungieAppCredentials {

    /** The only row. */
    public static final short ID = 1;

    @Id
    @Column(name = "id")
    private Short id = ID;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    /** Encrypted. */
    @Column(name = "client_secret_enc", nullable = false)
    private String clientSecretEnc;

    /** Encrypted. */
    @Column(name = "api_key_enc", nullable = false)
    private String apiKeyEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; null until first saved, so that a new row is persisted rather than merged. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Override
    public String toString() {
        // Never the secrets, even encrypted
        return "BungieAppCredentials[clientId=" + clientId + "]";
    }
}
