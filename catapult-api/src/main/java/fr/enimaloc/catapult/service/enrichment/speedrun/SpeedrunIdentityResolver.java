package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
import fr.enimaloc.catapult.repository.speedrun.SpeedrunAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Which speedrun.com account is a member's, tried in this order:
 * <ol>
 *   <li>the account of their personal API key, unless speedrun.com refused the key; the key is checked again
 *       ({@code GET /profile}) once its last check is older than {@value #KEY_RECHECK_DAYS} days: refused, it is
 *       marked invalid (only while the row still holds that key) and the next ways are tried; unreachable, the
 *       known account is kept;</li>
 *   <li>the only speedrun.com profile whose Twitch link is the member's Twitch login (none, or several, is none);</li>
 *   <li>the name they declared.</li>
 * </ol>
 * The Twitch and name lookups are cached ({@code speedrun-user}). Every upstream call asks the caller's budget (once
 * for the whole resolution) then the {@link SpeedrunRateLimiter}. A refused call skips its step: the answer is then
 * built from what is known and cached, and when that finds nothing the refusal is thrown, so that "no account" is
 * never said for "not looked up".
 */
@Slf4j
@Service
public class SpeedrunIdentityResolver {

    static final long KEY_RECHECK_DAYS = 7;
    private static final Duration KEY_RECHECK = Duration.ofDays(KEY_RECHECK_DAYS);
    private static final String USER_PAGE = "https://www.speedrun.com/users/";

    private final SpeedrunAccountRepository accounts;
    private final EnrichmentCache cache;
    private final SpeedrunClient client;
    private final SpeedrunRateLimiter limiter;
    private final TokenEncryptionService encryption;
    private final Clock clock;

    @Autowired
    public SpeedrunIdentityResolver(SpeedrunAccountRepository accounts, EnrichmentCache cache, SpeedrunClient client,
                                    SpeedrunRateLimiter limiter, TokenEncryptionService encryption) {
        this(accounts, cache, client, limiter, encryption, Clock.systemUTC());
    }

    SpeedrunIdentityResolver(SpeedrunAccountRepository accounts, EnrichmentCache cache, SpeedrunClient client,
                             SpeedrunRateLimiter limiter, TokenEncryptionService encryption, Clock clock) {
        this.accounts   = accounts;
        this.cache      = cache;
        this.client     = client;
        this.limiter    = limiter;
        this.encryption = encryption;
        this.clock      = clock;
    }

    /**
     * The member's speedrun.com account; empty when none is found.
     *
     * @throws UpstreamBudgetExceededException when nothing is found and a step was skipped for want of budget
     */
    public Optional<SpeedrunIdentity> resolve(UserAccount user, FetchBudget budget) {
        Gate gate = new Gate(FetchBudget.once(budget), limiter.member());
        Optional<SpeedrunAccount> row = accounts.findById(user.getId());

        Optional<SpeedrunIdentity> found = row.flatMap(account -> byKey(account, gate))
                .or(() -> byTwitch(user.getTwitchUsername(), gate))
                .or(() -> row.map(SpeedrunAccount::getDeclaredUsername).flatMap(name -> byName(name, gate)));
        if (found.isEmpty() && gate.refused != null) {
            throw gate.refused;
        }
        return found;
    }

    /**
     * The key's account. The row read here is never changed nor saved (it may be managed by the caller's
     * transaction): the checks write through conditional updates, and the answer is built from what they wrote.
     */
    private Optional<SpeedrunIdentity> byKey(SpeedrunAccount account, Gate gate) {
        if (account.getApiKeyEnc() == null || account.isKeyInvalid() || account.getKeyUserId() == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant checked = account.getKeyCheckedAt();
        if ((checked == null || !checked.plus(KEY_RECHECK).isAfter(now)) && gate.tryAcquire()) {
            Optional<SpeedrunUser> profile;
            try {
                profile = client.profile(encryption.decrypt(account.getApiKeyEnc()));
            } catch (RuntimeException e) {
                // Unreachable, rate limited or undecryptable: not a refusal, the known account stays.
                log.warn("speedrun.com key of {} not rechecked: {}", account.getUserId(), e.getClass().getSimpleName());
                return Optional.of(keyIdentity(account.getKeyUserId(), account.getKeyUserName()));
            }
            if (profile.isEmpty()) {
                accounts.markKeyInvalid(account.getUserId(), account.getApiKeyEnc(), now);
                return Optional.empty();
            }
            SpeedrunUser owner = profile.get();
            if (accounts.keyRechecked(account.getUserId(), account.getApiKeyEnc(), owner.id(), owner.name(), now) == 0) {
                // The member removed or replaced the key meanwhile: it is no longer theirs to vouch for.
                return Optional.empty();
            }
            return Optional.of(keyIdentity(owner.id(), owner.name()));
        }
        return Optional.of(keyIdentity(account.getKeyUserId(), account.getKeyUserName()));
    }

    private Optional<SpeedrunIdentity> byTwitch(String login, Gate gate) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        String lower = login.toLowerCase(Locale.ROOT);
        return cached("twitch:" + lower, gate, () -> onlyLinkTo(lower, client.usersByTwitch(login)))
                .map(user -> identity(user, SpeedrunIdentity.Source.TWITCH));
    }

    private Optional<SpeedrunIdentity> byName(String name, Gate gate) {
        return cached("name:" + name.toLowerCase(Locale.ROOT), gate, () -> client.user(name))
                .map(user -> identity(user, SpeedrunIdentity.Source.DECLARED));
    }

    /** The one account answered, if its Twitch link ends with {@code /<login>}; none, several or another: none. */
    private static Optional<SpeedrunUser> onlyLinkTo(String lowerLogin, List<SpeedrunUser> users) {
        if (users.size() != 1) {
            return Optional.empty();
        }
        SpeedrunUser user = users.getFirst();
        return user.twitchUri() != null && user.twitchUri().toLowerCase(Locale.ROOT).endsWith("/" + lowerLogin)
                ? Optional.of(user)
                : Optional.empty();
    }

    private Optional<SpeedrunUser> cached(String key, Gate gate, Supplier<Optional<SpeedrunUser>> fetch) {
        try {
            return cache.get(EnrichmentSource.SPEEDRUN_USER, key, SpeedrunUser.class, gate, fetch);
        } catch (UpstreamBudgetExceededException e) {
            return Optional.empty();
        }
    }

    private static SpeedrunIdentity keyIdentity(String userId, String name) {
        String url = name == null ? null : USER_PAGE + URLEncoder.encode(name, StandardCharsets.UTF_8);
        return new SpeedrunIdentity(userId, name, url, SpeedrunIdentity.Source.API_KEY);
    }

    private static SpeedrunIdentity identity(SpeedrunUser user, SpeedrunIdentity.Source source) {
        return new SpeedrunIdentity(user.id(), user.name(), user.url(), source);
    }

    /** The caller's budget (asked once for the whole resolution) then the limiter's; remembers the first refusal. */
    private static final class Gate implements FetchBudget {

        private final FetchBudget caller;
        private final FetchBudget limiter;
        private FetchBudget refusedBy;
        private UpstreamBudgetExceededException refused;

        Gate(FetchBudget caller, FetchBudget limiter) {
            this.caller  = caller;
            this.limiter = limiter;
        }

        @Override
        public boolean tryAcquire() {
            FetchBudget refuser = !caller.tryAcquire() ? caller : !limiter.tryAcquire() ? limiter : null;
            if (refuser != null && refused == null) {
                refusedBy = refuser;
                refused = new UpstreamBudgetExceededException(refuser.retryAfter());
            }
            return refuser == null;
        }

        @Override
        public Duration retryAfter() {
            return refusedBy == null ? caller.retryAfter() : refusedBy.retryAfter();
        }
    }
}
