package fr.enimaloc.catapult.service.speedrun;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.SpeedrunData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
import fr.enimaloc.catapult.repository.speedrun.SpeedrunAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunClient;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentityResolver;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRateLimiter;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A member's speedrun.com settings: the name they declare and their personal API key. Both are checked with
 * speedrun.com before anything is written (an unknown name or a refused key is a 400, speedrun.com out of reach a
 * 503). The key is stored encrypted and never given back, logged or put in a URL. The row is deleted once it holds
 * neither a name nor a key.
 */
@Slf4j
@Service
public class SpeedrunAccountService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_\\-.]{1,30}");
    private static final Pattern API_KEY = Pattern.compile("[A-Za-z0-9]{1,64}");

    private final SpeedrunAccountRepository accounts;
    private final SpeedrunClient client;
    private final SpeedrunRateLimiter limiter;
    private final TokenEncryptionService encryption;
    private final SpeedrunIdentityResolver resolver;
    private final EnrichmentCache cache;
    private final Clock clock;

    @Autowired
    public SpeedrunAccountService(SpeedrunAccountRepository accounts, SpeedrunClient client,
                                  SpeedrunRateLimiter limiter, TokenEncryptionService encryption,
                                  SpeedrunIdentityResolver resolver, EnrichmentCache cache) {
        this(accounts, client, limiter, encryption, resolver, cache, Clock.systemUTC());
    }

    SpeedrunAccountService(SpeedrunAccountRepository accounts, SpeedrunClient client, SpeedrunRateLimiter limiter,
                           TokenEncryptionService encryption, SpeedrunIdentityResolver resolver,
                           EnrichmentCache cache, Clock clock) {
        this.accounts   = accounts;
        this.client     = client;
        this.limiter    = limiter;
        this.encryption = encryption;
        this.resolver   = resolver;
        this.cache      = cache;
        this.clock      = clock;
    }

    /** Declares {@code username} (trimmed) as the member's speedrun.com name, once speedrun.com knows it. */
    public void saveUsername(UserAccount user, String username) {
        String name = username == null ? "" : username.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid speedrun.com username");
        }
        SpeedrunUser found = upstream(() -> client.user(name))
                .orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown speedrun.com username"));
        cache.put(EnrichmentSource.SPEEDRUN_USER, "name:" + name.toLowerCase(Locale.ROOT), found);
        SpeedrunAccount row = rowOf(user);
        row.setDeclaredUsername(name);
        row.setUpdatedAt(clock.instant());
        accounts.save(row);
    }

    public void deleteUsername(UserAccount user) {
        accounts.findById(user.getId()).ifPresent(row -> {
            row.setDeclaredUsername(null);
            saveOrDelete(row);
        });
    }

    /** Saves the member's personal API key (trimmed), encrypted, once speedrun.com accepts it. */
    public void saveApiKey(UserAccount user, String apiKey) {
        String key = apiKey == null ? "" : apiKey.strip();
        if (!API_KEY.matcher(key).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid speedrun.com API key");
        }
        SpeedrunUser owner = upstream(() -> client.profile(key))
                .orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST, "speedrun.com refused this API key"));
        Instant now = clock.instant();
        SpeedrunAccount row = rowOf(user);
        row.setApiKeyEnc(encryption.encrypt(key));
        row.setKeyUserId(owner.id());
        row.setKeyUserName(owner.name());
        row.setKeyCheckedAt(now);
        row.setKeyInvalid(false);
        row.setUpdatedAt(now);
        accounts.save(row);
    }

    public void deleteApiKey(UserAccount user) {
        accounts.findById(user.getId()).ifPresent(row -> {
            row.setApiKeyEnc(null);
            row.setKeyUserId(null);
            row.setKeyUserName(null);
            row.setKeyCheckedAt(null);
            row.setKeyInvalid(false);
            saveOrDelete(row);
        });
    }

    /** The settings card's data; built from the row and the cache only, never calling speedrun.com. */
    public SpeedrunData data(UserAccount user) {
        Optional<SpeedrunAccount> row = accounts.findById(user.getId());
        Optional<SpeedrunIdentity> resolved;
        try {
            resolved = resolver.resolve(user, () -> false);
        } catch (UpstreamBudgetExceededException e) {
            resolved = Optional.empty();
        }
        return new SpeedrunData(
                row.map(SpeedrunAccount::getDeclaredUsername).orElse(null),
                row.map(SpeedrunAccount::getApiKeyEnc).isPresent(),
                row.map(SpeedrunAccount::isKeyInvalid).orElse(false),
                row.map(SpeedrunAccount::getKeyUserName).orElse(null),
                resolved.map(SpeedrunIdentity::name).orElse(null),
                resolved.map(SpeedrunIdentity::url).orElse(null),
                resolved.map(identity -> identity.source().key()).orElse(null));
    }

    private SpeedrunAccount rowOf(UserAccount user) {
        return accounts.findById(user.getId()).orElseGet(() -> new SpeedrunAccount(user.getId()));
    }

    private void saveOrDelete(SpeedrunAccount row) {
        if (row.isEmpty()) {
            accounts.delete(row);
        } else {
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
        }
    }

    /** One call to speedrun.com within the limiter; out of reach (or limited) is a 503, never "unknown". */
    private <T> Optional<T> upstream(Supplier<Optional<T>> call) {
        if (!limiter.member().tryAcquire()) {
            throw new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "speedrun.com is busy, try again in a minute");
        }
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("speedrun.com settings check failed: {}", e.getClass().getSimpleName());
            throw new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "speedrun.com cannot be reached right now");
        }
    }
}
