package fr.enimaloc.catapult.service.retroachievements;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.RetroAchievementsData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsAccount;
import fr.enimaloc.catapult.getter.retroachievements.RetroAchievementsApiKeyRotator;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.rastats.RaCredentials;
import fr.enimaloc.catapult.service.enrichment.rastats.RaKeyRejectedException;
import fr.enimaloc.catapult.service.enrichment.rastats.RaUser;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsClient;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A member's RetroAchievements settings: the name they declare and, optionally, their personal web API key. The
 * name is checked with RetroAchievements (which also gives the ULID later calls use), the key by one profile call
 * (an unknown name or a refused key is a 400, RetroAchievements out of reach or no key to ask with a 503). The key
 * is stored encrypted and never given back, logged or put in a URL. The row exists only while it holds a name.
 * Each write re-reads the row and is optimistically locked: one that crosses another is redone once on the fresh
 * row, then answers 409. Telling the owner's pages comes after the write and never fails it.
 */
@Slf4j
@Service
public class RetroAchievementsAccountService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{2,32}");
    private static final Pattern API_KEY = Pattern.compile("[A-Za-z0-9]{16,64}");
    private static final String PROFILE = "https://retroachievements.org/user/";

    private final RetroAchievementsAccountRepository accounts;
    private final RetroAchievementsClient client;
    private final TokenEncryptionService encryption;
    private final Optional<RetroAchievementsApiKeyRotator> rotator;
    private final EnrichmentCache cache;
    private final ChannelEventPublisher events;
    private final Clock clock;

    @Autowired
    public RetroAchievementsAccountService(RetroAchievementsAccountRepository accounts,
                                           RetroAchievementsClient client, TokenEncryptionService encryption,
                                           Optional<RetroAchievementsApiKeyRotator> rotator, EnrichmentCache cache,
                                           ChannelEventPublisher events) {
        this(accounts, client, encryption, rotator, cache, events, Clock.systemUTC());
    }

    RetroAchievementsAccountService(RetroAchievementsAccountRepository accounts, RetroAchievementsClient client,
                                    TokenEncryptionService encryption,
                                    Optional<RetroAchievementsApiKeyRotator> rotator, EnrichmentCache cache,
                                    ChannelEventPublisher events, Clock clock) {
        this.accounts   = accounts;
        this.client     = client;
        this.encryption = encryption;
        this.rotator    = rotator;
        this.cache      = cache;
        this.events     = events;
        this.clock      = clock;
    }

    /**
     * The key a call for {@code user} is sent with: their personal one while RetroAchievements has not refused it,
     * else the next of the shared pool; empty when there is none (no pool, or an empty one).
     */
    public Optional<RaCredentials> credentials(UserAccount user, Optional<RetroAchievementsAccount> row) {
        Optional<RaCredentials> personal = row.filter(r -> r.getApiKeyEnc() != null && !r.isKeyInvalid())
                .flatMap(r -> decrypt(user, r.getApiKeyEnc())).map(key -> new RaCredentials(key, true));
        if (personal.isPresent()) {
            return personal;
        }
        return rotator.flatMap(RetroAchievementsApiKeyRotator::nextKey).map(key -> new RaCredentials(key, false));
    }

    private Optional<String> decrypt(UserAccount user, String encrypted) {
        try {
            return Optional.ofNullable(encryption.decrypt(encrypted));
        } catch (RuntimeException e) {
            log.warn("RetroAchievements key of {} cannot be decrypted: {}", user.getId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Declares {@code username} (trimmed) as the member's RetroAchievements name, once RetroAchievements knows it. */
    public void saveUsername(UserAccount user, String username) {
        String name = username == null ? "" : username.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid RetroAchievements username");
        }
        RaUser found = lookUp(user, name)
                .orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown RetroAchievements username"));
        write(user.getId(), true, row -> {
            row.setUsername(found.user());
            row.setUserUlid(found.ulid());
            return true;
        });
        cache.evictUser(user.getId());
        publish(user.getId());
    }

    /**
     * The profile of {@code name} asked with the member's personal key, else the pool's. A personal key refused is
     * marked so and the pool takes over; a pool key refused (the rotator sets it aside) is an outage here.
     */
    private Optional<RaUser> lookUp(UserAccount user, String name) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<RetroAchievementsAccount> row = accounts.findById(user.getId());
            RaCredentials credentials = credentials(user, row).orElseThrow(() -> new ApiProblem(
                    ErrorCode.SERVICE_UNAVAILABLE, "RetroAchievements cannot be asked right now"));
            try {
                return upstream(() -> client.profile(name, credentials));
            } catch (RaKeyRejectedException e) {
                if (!credentials.personal()) {
                    throw unreachable();
                }
                markKeyInvalid(user.getId());
            }
        }
        throw unreachable();
    }

    public void deleteUsername(UserAccount user) {
        if (write(user.getId(), false, row -> {
            row.setUsername(null);
            return true;
        })) {
            cache.evictUser(user.getId());
            publish(user.getId());
        }
    }

    /** Saves the member's personal key (trimmed), encrypted, once RetroAchievements accepts it for their account. */
    public void saveApiKey(UserAccount user, String apiKey) {
        String key = apiKey == null ? "" : apiKey.strip();
        if (!API_KEY.matcher(key).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid RetroAchievements API key");
        }
        RetroAchievementsAccount row = accounts.findById(user.getId()).orElseThrow(() -> new ApiProblem(
                ErrorCode.BAD_REQUEST, "Declare your RetroAchievements username first"));
        try {
            upstream(() -> client.profile(row.getUserUlid(), new RaCredentials(key, true)))
                    .orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown RetroAchievements account"));
        } catch (RaKeyRejectedException e) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "RetroAchievements refused this API key");
        }
        String encrypted = encryption.encrypt(key);
        if (!write(user.getId(), false, current -> {
            current.setApiKeyEnc(encrypted);
            current.setKeyInvalid(false);
            return true;
        })) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Declare your RetroAchievements username first");
        }
        publish(user.getId());
    }

    public void deleteApiKey(UserAccount user) {
        if (write(user.getId(), false, row -> {
            if (row.getApiKeyEnc() == null && !row.isKeyInvalid()) {
                return false;
            }
            row.setApiKeyEnc(null);
            row.setKeyInvalid(false);
            return true;
        })) {
            publish(user.getId());
        }
    }

    /** RetroAchievements refused the member's personal key at use: it is not tried again until saved anew. */
    public void markKeyInvalid(UUID userId) {
        if (write(userId, false, row -> {
            if (row.getApiKeyEnc() == null || row.isKeyInvalid()) {
                return false;
            }
            row.setKeyInvalid(true);
            return true;
        })) {
            publish(userId);
        }
    }

    /** The settings card's data; built from the row (read once) only, never calling RetroAchievements. */
    public RetroAchievementsData data(UserAccount user) {
        return data(user.getId());
    }

    private RetroAchievementsData data(UUID userId) {
        Optional<RetroAchievementsAccount> row = accounts.findById(userId);
        String username = row.map(RetroAchievementsAccount::getUsername).orElse(null);
        return new RetroAchievementsData(username, username == null ? null : PROFILE + username,
                row.map(RetroAchievementsAccount::getApiKeyEnc).isPresent(),
                row.map(RetroAchievementsAccount::isKeyInvalid).orElse(false),
                rotator.map(RetroAchievementsApiKeyRotator::hasKeys).orElse(false));
    }

    /** Tells the owner's open pages how the settings now stand. */
    private void publish(UUID userId) {
        try {
            events.retroAchievementsAccountChanged(userId, data(userId));
        } catch (RuntimeException e) {
            // The settings are saved: a page that is not told refreshes on its next load.
            log.warn("RetroAchievements settings of {} saved but not pushed: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Applies {@code change} to the member's row as it is now ({@code create}: a missing row is started), then saves
     * it, or deletes it once it holds no name. A write that crosses another one is redone once on the fresh row,
     * then is a 409.
     *
     * @return false when nothing was written: no row (and {@code create} false) or {@code change} changed nothing
     */
    private boolean write(UUID userId, boolean create, Predicate<RetroAchievementsAccount> change) {
        try {
            return writeOnce(userId, create, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, create, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("RetroAchievements settings of {} crossed another write twice: {}", userId,
                        again.getClass().getSimpleName());
                throw new ApiProblem(ErrorCode.CONFLICT,
                        "The RetroAchievements settings changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, boolean create, Predicate<RetroAchievementsAccount> change) {
        Optional<RetroAchievementsAccount> found = accounts.findById(userId);
        if (found.isEmpty() && !create) {
            return false;
        }
        RetroAchievementsAccount row = found.orElseGet(() -> new RetroAchievementsAccount(userId));
        if (!change.test(row)) {
            return false;
        }
        if (row.getUsername() == null) {
            accounts.delete(row);
        } else {
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
        }
        return true;
    }

    /** One call to RetroAchievements; out of reach (or limited) is a 503, never "unknown". A refused key passes. */
    private <T> Optional<T> upstream(Supplier<Optional<T>> call) {
        try {
            return call.get();
        } catch (RaKeyRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("RetroAchievements settings check failed: {}", e.getClass().getSimpleName());
            throw unreachable();
        }
    }

    private static ApiProblem unreachable() {
        return new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "RetroAchievements cannot be reached right now");
    }
}
