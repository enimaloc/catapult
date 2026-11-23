package fr.enimaloc.catapult.service.osu;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.admin.OsuCredentialsData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.osu.OsuAppCredentials;
import fr.enimaloc.catapult.repository.osu.OsuAccountRepository;
import fr.enimaloc.catapult.repository.osu.OsuAppCredentialsRepository;
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
 * The osu! OAuth application Catapult reads the members' osu! profiles with (client id and secret), which an admin
 * sets. The secret is stored encrypted, never given back, never logged. The decrypted values are kept in memory for
 * {@link #MEMO}, so that another instance's change is picked up shortly; a change made here is seen at once. Without
 * them the osu! source is off and the {@code osu} login is not registered.
 */
@Slf4j
@Service
public class OsuCredentialsService {

    /** How long the decrypted credentials are reused before the row is read again. */
    static final Duration MEMO = Duration.ofSeconds(30);

    /** osu!'s client ids are numbers. */
    private static final Pattern CLIENT_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern CLIENT_SECRET = Pattern.compile("[A-Za-z0-9._~+/=-]{16,128}");

    /** The decrypted credentials. {@link #toString()} never shows the secret. */
    public record Credentials(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "Credentials[clientId=" + clientId + ", clientSecret=***]";
        }
    }

    private record Memo(Optional<Credentials> value, Instant until) {
    }

    private final OsuAppCredentialsRepository repository;
    private final TokenEncryptionService encryption;
    private final OsuAccountRepository links;
    private final Clock clock;
    private volatile Memo memo;

    @Autowired
    public OsuCredentialsService(OsuAppCredentialsRepository repository, TokenEncryptionService encryption,
                                 OsuAccountRepository links) {
        this(repository, encryption, links, Clock.systemUTC());
    }

    OsuCredentialsService(OsuAppCredentialsRepository repository, TokenEncryptionService encryption,
                          OsuAccountRepository links, Clock clock) {
        this.links = links;
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

    /** Whether the application is set: the osu! source and login are on. */
    public boolean available() {
        return current().isPresent();
    }

    /** What the admin Keys tab shows: the client id, never the secret. */
    public OsuCredentialsData page() {
        Optional<OsuAppCredentials> row = repository.findById(OsuAppCredentials.ID);
        return row.map(r -> new OsuCredentialsData(true, r.getClientId(), r.getUpdatedAt()))
                .orElseGet(OsuCredentialsData::none);
    }

    /**
     * Sets the application. Every value is trimmed; a blank secret keeps the one set (400 when there is none yet, or
     * the client id changes: the secret belongs to the application), a malformed value is a 400. A write crossing
     * another one is redone once, then is a 409.
     */
    public void save(String clientId, String clientSecret) {
        String id = trim(clientId);
        String secret = trim(clientSecret);
        if (!CLIENT_ID.matcher(id).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid osu! OAuth client id");
        }
        if (!secret.isEmpty() && !CLIENT_SECRET.matcher(secret).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid osu! OAuth client secret");
        }
        boolean newApplication;
        try {
            newApplication = saveOnce(id, secret);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                newApplication = saveOnce(id, secret);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                throw new ApiProblem(ErrorCode.CONFLICT, "The osu! credentials changed meanwhile, try again");
            }
        }
        memo = null;
        if (newApplication) {
            // A refresh token belongs to the client that issued it: the links made with another one are dead, each
            // member is asked to link again (rather than shown linked and unavailable)
            int invalidated = links.invalidateAll(clock.instant());
            log.info("osu! application credentials set (client {}, a new application: {} link(s) to make again)",
                    id, invalidated);
        } else {
            log.info("osu! application credentials set (client {})", id);
        }
    }

    /** Removes the application: the osu! source and login go off. */
    public void delete() {
        repository.deleteAllInBatch();
        memo = null;
        log.info("osu! application credentials removed");
    }

    /** Writes the row; true when it is another application than the one set (or none was). */
    private boolean saveOnce(String id, String secret) {
        Optional<OsuAppCredentials> found = repository.findById(OsuAppCredentials.ID);
        boolean newApplication = found.isEmpty() || !found.get().getClientId().equals(id);
        if (newApplication && secret.isEmpty()) {
            // The secret belongs to the application: another client id needs its own
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "The client secret is needed");
        }
        OsuAppCredentials row = found.orElseGet(OsuAppCredentials::new);
        row.setClientId(id);
        if (!secret.isEmpty()) {
            row.setClientSecretEnc(encryption.encrypt(secret));
        }
        row.setUpdatedAt(clock.instant());
        repository.save(row);
        return newApplication;
    }

    private Optional<Credentials> read() {
        Optional<OsuAppCredentials> row = repository.findById(OsuAppCredentials.ID);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Credentials(row.get().getClientId(),
                    encryption.decrypt(row.get().getClientSecretEnc())));
        } catch (RuntimeException e) {
            // Never the value itself: the key may have changed without a rotation
            log.warn("The osu! application credentials cannot be decrypted: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
