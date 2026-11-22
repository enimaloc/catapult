package fr.enimaloc.catapult.service.bungie;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.admin.BungieCredentialsData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.bungie.BungieAppCredentials;
import fr.enimaloc.catapult.repository.bungie.BungieAppCredentialsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Bungie.net application Catapult reads Destiny 2 with (OAuth client id and secret, API key), which an admin
 * sets. The secret and the key are stored encrypted, never given back (the key is shown masked), never logged. The
 * decrypted values are kept in memory for {@link #MEMO}, so that another instance's change is picked up shortly; a
 * change made here is seen at once. Without them the Bungie source is off and the {@code bungie} login is not
 * registered.
 */
@Slf4j
@Service
public class BungieCredentialsService {

    /** How long the decrypted credentials are reused before the row is read again. */
    static final Duration MEMO = Duration.ofSeconds(30);

    /** Bungie's application ids are numbers. */
    private static final Pattern CLIENT_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern CLIENT_SECRET = Pattern.compile("[A-Za-z0-9._~+/=-]{16,128}");
    /** Bungie's API keys are 32 hexadecimal digits. */
    private static final Pattern API_KEY = Pattern.compile("[0-9A-Fa-f]{32}");

    /** The decrypted credentials. {@link #toString()} never shows the secrets. */
    public record Credentials(String clientId, String clientSecret, String apiKey) {
        @Override
        public String toString() {
            return "Credentials[clientId=" + clientId + ", clientSecret=***, apiKey=***]";
        }
    }

    private record Memo(Optional<Credentials> value, Instant until) {
    }

    private final BungieAppCredentialsRepository repository;
    private final TokenEncryptionService encryption;
    private final Clock clock;
    private volatile Memo memo;

    @Autowired
    public BungieCredentialsService(BungieAppCredentialsRepository repository, TokenEncryptionService encryption) {
        this(repository, encryption, Clock.systemUTC());
    }

    BungieCredentialsService(BungieAppCredentialsRepository repository, TokenEncryptionService encryption,
                             Clock clock) {
        this.repository = repository;
        this.encryption = encryption;
        this.clock = clock;
    }

    /** The credentials set, decrypted; empty when none is (or they cannot be decrypted). */
    public Optional<Credentials> current() {
        Memo known = memo;
        Instant now = clock.instant();
        if (known != null && now.isBefore(known.until())) {
            return known.value();
        }
        Optional<Credentials> read = read();
        memo = new Memo(read, now.plus(MEMO));
        return read;
    }

    /** Whether the application is set: the Bungie source and login are on. */
    public boolean available() {
        return current().isPresent();
    }

    /** What the admin Keys tab shows: the client id and the key masked, never the secret. */
    public BungieCredentialsData page() {
        Optional<BungieAppCredentials> row = repository.findById(BungieAppCredentials.ID);
        if (row.isEmpty()) {
            return BungieCredentialsData.none();
        }
        String masked;
        try {
            masked = mask(encryption.decrypt(row.get().getApiKeyEnc()));
        } catch (RuntimeException e) {
            masked = "…";
        }
        return new BungieCredentialsData(true, row.get().getClientId(), masked, row.get().getUpdatedAt());
    }

    /**
     * Sets the application. Every value is trimmed; a blank secret or key keeps the one set (400 when there is none
     * yet), a malformed value is a 400. A write crossing another one is redone once, then is a 409.
     */
    public void save(String clientId, String clientSecret, String apiKey) {
        String id = trim(clientId);
        String secret = trim(clientSecret);
        String key = trim(apiKey);
        if (!CLIENT_ID.matcher(id).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid Bungie OAuth client id");
        }
        if (!secret.isEmpty() && !CLIENT_SECRET.matcher(secret).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid Bungie OAuth client secret");
        }
        if (!key.isEmpty() && !API_KEY.matcher(key).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid Bungie API key");
        }
        try {
            saveOnce(id, secret, key);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                saveOnce(id, secret, key);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                throw new ApiProblem(ErrorCode.CONFLICT, "The Bungie credentials changed meanwhile, try again");
            }
        }
        memo = null;
        log.info("Bungie application credentials set (client {})", id);
    }

    /** Removes the application: the Bungie source and login go off. */
    public void delete() {
        repository.deleteAllInBatch();
        memo = null;
        log.info("Bungie application credentials removed");
    }

    private void saveOnce(String id, String secret, String key) {
        Optional<BungieAppCredentials> found = repository.findById(BungieAppCredentials.ID);
        if (found.isEmpty() && (secret.isEmpty() || key.isEmpty())) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "The client secret and the API key are needed");
        }
        BungieAppCredentials row = found.orElseGet(BungieAppCredentials::new);
        row.setClientId(id);
        if (!secret.isEmpty()) {
            row.setClientSecretEnc(encryption.encrypt(secret));
        }
        if (!key.isEmpty()) {
            row.setApiKeyEnc(encryption.encrypt(key));
        }
        row.setUpdatedAt(clock.instant());
        repository.save(row);
    }

    private Optional<Credentials> read() {
        Optional<BungieAppCredentials> row = repository.findById(BungieAppCredentials.ID);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Credentials(row.get().getClientId(),
                    encryption.decrypt(row.get().getClientSecretEnc()), encryption.decrypt(row.get().getApiKeyEnc())));
        } catch (RuntimeException e) {
            // Never the value itself: the key may have changed without a rotation
            log.warn("The Bungie application credentials cannot be decrypted: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
