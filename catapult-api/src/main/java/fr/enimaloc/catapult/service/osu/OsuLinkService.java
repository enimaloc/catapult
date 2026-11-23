package fr.enimaloc.catapult.service.osu;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.OsuAccountData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.osu.OsuAccount;
import fr.enimaloc.catapult.repository.osu.OsuAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.osu.OsuClient;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData;
import fr.enimaloc.catapult.service.enrichment.osu.OsuTokenService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A member's osu! link, made through osu!'s OAuth ({@code osu} registration, scopes {@code identify public}). The
 * login is checked first ({@link #check}: the application is set, the account the token reads is the one the token
 * was issued for) without writing anything; the link is written once Spring hands over the refresh token
 * ({@link #complete}), encrypted, in a transaction of its own under the member's token lock. Unlinking deletes the row,
 * forgets the member's osu! stats in the cache and their access token. Telling the owner's pages comes after the
 * commit and never fails it. No token is ever logged or put in a message.
 */
@Slf4j
@Service
public class OsuLinkService {

    /** Request attribute the checked login leaves its link in, for {@code TwitchLoginSuccessHandler}. */
    public static final String PENDING_ATTRIBUTE = "catapult.osu.pending-link";
    private static final List<EnrichmentSource> SOURCES = List.of(EnrichmentSource.OSU_PROFILE,
            EnrichmentSource.OSU_RECENT);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Why an osu! login is refused: the OAuth error code the card shows. */
    public enum Refusal {
        UNAVAILABLE("osu_unavailable"), MISMATCH("osu_mismatch"), FAILED("osu_failed");

        private final String code;

        Refusal(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class RefusedException extends RuntimeException {
        private final Refusal reason;

        public RefusedException(Refusal reason) {
            super("osu! login refused: " + reason);
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    /** A checked login, not written yet. Names and ids only. */
    public record PendingLink(long osuUserId, String username, String playmode) {
    }

    private final OsuAccountRepository accounts;
    private final OsuClient client;
    private final OsuCredentialsService credentials;
    private final OsuTokenService tokens;
    private final TokenEncryptionService encryption;
    private final EnrichmentCache cache;
    private final ChannelEventPublisher events;
    private final Clock clock;
    /** A transaction of its own (REQUIRES_NEW): written and committed before anything is told, even in a caller's. */
    private final TransactionTemplate transactions;

    @Autowired
    public OsuLinkService(OsuAccountRepository accounts, OsuClient client, OsuCredentialsService credentials,
                          OsuTokenService tokens, TokenEncryptionService encryption, EnrichmentCache cache,
                          ChannelEventPublisher events, PlatformTransactionManager transactionManager) {
        this(accounts, client, credentials, tokens, encryption, cache, events, transactionManager, Clock.systemUTC());
    }

    OsuLinkService(OsuAccountRepository accounts, OsuClient client, OsuCredentialsService credentials,
                   OsuTokenService tokens, TokenEncryptionService encryption, EnrichmentCache cache,
                   ChannelEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.accounts = accounts;
        this.client = client;
        this.credentials = credentials;
        this.tokens = tokens;
        this.encryption = encryption;
        this.cache = cache;
        this.events = events;
        this.clock = clock;
    }

    /** Whether linking is offered: an osu! application is set. */
    public boolean available() {
        return credentials.available();
    }

    /**
     * Checks an osu! login: reads the account {@code accessToken} belongs to ({@code GET /me}). When the token is a
     * JWT naming its account ({@code sub}, as osu!'s are), that account must be the one read. Nothing is written.
     *
     * @throws RefusedException {@code UNAVAILABLE} without an application, {@code MISMATCH} for another account,
     *         {@code FAILED} when osu! cannot be read
     */
    public PendingLink check(String accessToken) {
        if (!available()) {
            throw new RefusedException(Refusal.UNAVAILABLE);
        }
        OsuData.Me me;
        try {
            me = client.me(accessToken);
        } catch (RuntimeException e) {
            // Its type only: never the token
            log.warn("osu! login: the account could not be read: {}", e.getClass().getSimpleName());
            throw new RefusedException(Refusal.FAILED);
        }
        Optional<String> named = tokenSubject(accessToken);
        if (named.isPresent() && !named.get().equals(Long.toString(me.id()))) {
            log.warn("osu! login: the token named another account than the one it reads");
            throw new RefusedException(Refusal.MISMATCH);
        }
        return new PendingLink(me.id(), me.username(), me.playmode());
    }

    /**
     * Writes the member's link from a checked login and the refresh token osu! gave with it (encrypted), holding the
     * access token the login came with (valid 24 h) so that the first read does not renew it at once. The row is
     * written in a transaction of its own under the member's token lock (no renewal crosses it); the cache, the held
     * tokens and the pages follow once it is committed. Linking another osu! account than the one linked forgets
     * what was cached for the former.
     */
    public void complete(UserAccount user, PendingLink pending, String refreshToken, String accessToken,
                         Instant accessExpiresAt) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RefusedException(Refusal.FAILED);
        }
        UUID userId = user.getId();
        boolean changed = tokens.locked(userId, () -> {
            boolean replaced = write(userId, pending, refreshToken);
            tokens.evict(userId);
            tokens.seed(userId, accessToken, accessExpiresAt);
            return replaced;
        });
        if (changed) {
            cache.evictUser(userId, SOURCES);
        }
        tell(userId);
    }

    /**
     * Unlinks: deletes the row (whatever its version) in a transaction of its own under the member's token lock,
     * then, once committed, forgets the cached stats and the access token and tells the pages.
     */
    public void unlink(UserAccount user) {
        UUID userId = user.getId();
        tokens.locked(userId, () -> {
            transactions.executeWithoutResult(status -> accounts.purgeByUserId(userId));
            tokens.evict(userId);
            return null;
        });
        cache.evictUser(userId, SOURCES);
        tell(userId);
    }

    /** The card of {@code user}; null when osu! is not set up and nothing is linked (no card at all). */
    public OsuAccountData data(UserAccount user) {
        return data(user.getId());
    }

    @EventListener
    public void onLinkInvalidated(OsuTokenService.LinkInvalidated event) {
        tell(event.userId());
    }

    private OsuAccountData data(UUID userId) {
        boolean available = available();
        Optional<OsuAccount> row = accounts.findById(userId);
        if (row.isEmpty()) {
            return available ? OsuAccountData.unlinked(true) : null;
        }
        OsuAccount account = row.get();
        return new OsuAccountData(true, account.getUsername(), !account.usable(), available, account.getLinkedAt());
    }

    private void tell(UUID userId) {
        try {
            OsuAccountData data = data(userId);
            events.osuAccountChanged(userId, data == null ? OsuAccountData.unlinked(false) : data);
        } catch (RuntimeException e) {
            log.warn("Could not tell the pages of {} about their osu! link: {}", userId, e.getClass().getSimpleName());
        }
    }

    /** Writes the link; true when it is another osu! account than the one linked (or none was). */
    private boolean write(UUID userId, PendingLink pending, String refreshToken) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> writeOnce(userId, pending, refreshToken)));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return Boolean.TRUE.equals(transactions.execute(status -> writeOnce(userId, pending, refreshToken)));
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("The osu! link of {} crossed another write twice", userId);
                throw new ApiProblem(ErrorCode.CONFLICT, "The osu! link changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, PendingLink pending, String refreshToken) {
        Instant now = clock.instant();
        Optional<OsuAccount> found = accounts.findById(userId);
        OsuAccount row = found.orElseGet(() -> new OsuAccount(userId));
        boolean changed = found.isEmpty() || row.getOsuUserId() == null || row.getOsuUserId() != pending.osuUserId();
        row.setOsuUserId(pending.osuUserId());
        row.setUsername(pending.username());
        row.setPlaymode(pending.playmode());
        row.setRefreshTokenEnc(encryption.encrypt(refreshToken));
        row.setInvalidSince(null);
        if (changed) {
            row.setLinkedAt(now);
        }
        row.setUpdatedAt(now);
        accounts.saveAndFlush(row);
        return changed;
    }

    /** The {@code sub} claim of a JWT access token, read without trusting it further; empty when not a JWT. */
    static Optional<String> tokenSubject(String accessToken) {
        if (accessToken == null) {
            return Optional.empty();
        }
        String[] parts = accessToken.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode claims = MAPPER.readTree(new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8));
            JsonNode sub = claims.path("sub");
            return sub.isString() && !sub.asString().isBlank() ? Optional.of(sub.asString())
                    : sub.isIntegralNumber() ? Optional.of(sub.asString()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
