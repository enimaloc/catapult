package fr.enimaloc.catapult.service.bungie;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.BungieAccountData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import fr.enimaloc.catapult.repository.bungie.BungieAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieClient;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieTokenService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A member's Bungie.net link, made through Bungie's OAuth ({@code bungie} registration). The login is checked first
 * ({@link #check}: the application is set, the token's {@code membership_id} is the account Bungie says the token
 * belongs to) without writing anything; the link is written once Spring hands over the refresh token
 * ({@link #complete}), encrypted. Unlinking deletes the row, forgets the member's Bungie stats in the cache and their
 * access token. Telling the owner's pages comes after the write and never fails it. No token is ever logged or put
 * in a message.
 */
@Slf4j
@Service
public class BungieLinkService {

    /** Request attribute the checked login leaves its link in, for {@code TwitchLoginSuccessHandler}. */
    public static final String PENDING_ATTRIBUTE = "catapult.bungie.pending-link";
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(90);
    private static final List<EnrichmentSource> SOURCES = List.of(EnrichmentSource.BUNGIE_PROFILE,
            EnrichmentSource.BUNGIE_TRIUMPHS);

    /** Why a Bungie login is refused: the OAuth error code the card shows. */
    public enum Refusal {
        UNAVAILABLE("bungie_unavailable"), MISMATCH("bungie_mismatch"), FAILED("bungie_failed");

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
            super("Bungie login refused: " + reason);
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    /** A checked login, not written yet. Names and ids only. */
    public record PendingLink(String membershipId, String displayName, Integer membershipType,
                              String destinyMembershipId, Instant refreshExpiresAt) {
    }

    private final BungieAccountRepository accounts;
    private final BungieClient client;
    private final BungieCredentialsService credentials;
    private final BungieTokenService tokens;
    private final TokenEncryptionService encryption;
    private final EnrichmentCache cache;
    private final ChannelEventPublisher events;
    private final Clock clock;

    @Autowired
    public BungieLinkService(BungieAccountRepository accounts, BungieClient client,
                             BungieCredentialsService credentials, BungieTokenService tokens,
                             TokenEncryptionService encryption, EnrichmentCache cache,
                             ChannelEventPublisher events) {
        this(accounts, client, credentials, tokens, encryption, cache, events, Clock.systemUTC());
    }

    BungieLinkService(BungieAccountRepository accounts, BungieClient client, BungieCredentialsService credentials,
                      BungieTokenService tokens, TokenEncryptionService encryption, EnrichmentCache cache,
                      ChannelEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.client = client;
        this.credentials = credentials;
        this.tokens = tokens;
        this.encryption = encryption;
        this.cache = cache;
        this.events = events;
        this.clock = clock;
    }

    /** Whether linking is offered: a Bungie application is set. */
    public boolean available() {
        return credentials.available();
    }

    /**
     * Checks a Bungie login: the account {@code accessToken} belongs to must be the one the token answer named
     * ({@code membership_id} of {@code tokenParameters}). Nothing is written.
     *
     * @throws RefusedException {@code UNAVAILABLE} without an application, {@code MISMATCH} for another account,
     *         {@code FAILED} when Bungie cannot be read
     */
    public PendingLink check(String accessToken, Map<String, Object> tokenParameters) {
        if (!available()) {
            throw new RefusedException(Refusal.UNAVAILABLE);
        }
        Object named = tokenParameters == null ? null : tokenParameters.get("membership_id");
        if (named == null || String.valueOf(named).isBlank()) {
            throw new RefusedException(Refusal.FAILED);
        }
        BungieData.CurrentUser user;
        try {
            user = client.currentUser(accessToken);
        } catch (RuntimeException e) {
            // Its type only: never the token
            log.warn("Bungie login: the account could not be read: {}", e.getClass().getSimpleName());
            throw new RefusedException(Refusal.FAILED);
        }
        if (!user.membershipId().equals(String.valueOf(named))) {
            log.warn("Bungie login: the token named another account than the one it reads");
            throw new RefusedException(Refusal.MISMATCH);
        }
        BungieData.DestinyMembership destiny = user.destiny();
        Object refreshIn = tokenParameters.get("refresh_expires_in");
        Instant refreshExpiresAt = clock.instant().plus(refreshIn instanceof Number n ? Duration.ofSeconds(n.longValue())
                : parseSeconds(refreshIn));
        return new PendingLink(user.membershipId(), user.displayName(),
                destiny == null ? null : destiny.membershipType(), destiny == null ? null : destiny.membershipId(),
                refreshExpiresAt);
    }

    /**
     * Writes the member's link from a checked login and the refresh token Bungie gave with it (encrypted). Linking
     * another Bungie.net account than the one linked forgets what was cached for the former.
     */
    public void complete(UserAccount user, PendingLink pending, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RefusedException(Refusal.FAILED);
        }
        UUID userId = user.getId();
        boolean changed = write(userId, pending, refreshToken);
        tokens.evict(userId);
        if (changed) {
            cache.evictUser(userId, SOURCES);
        }
        tell(userId);
    }

    /** Unlinks: deletes the row (whatever its version), forgets the cached stats and the access token. */
    @Transactional
    public void unlink(UserAccount user) {
        UUID userId = user.getId();
        accounts.purgeByUserId(userId);
        cache.evictUser(userId, SOURCES);
        tokens.evict(userId);
        tell(userId);
    }

    /** The card of {@code user}; null when Bungie is not set up and nothing is linked (no card at all). */
    public BungieAccountData data(UserAccount user) {
        return data(user.getId());
    }

    @EventListener
    public void onLinkInvalidated(BungieTokenService.LinkInvalidated event) {
        tell(event.userId());
    }

    private BungieAccountData data(UUID userId) {
        boolean available = available();
        Optional<BungieAccount> row = accounts.findById(userId);
        if (row.isEmpty()) {
            return available ? BungieAccountData.unlinked(true) : null;
        }
        BungieAccount account = row.get();
        return new BungieAccountData(true, account.getDisplayName(), BungieData.platform(account.getMembershipType()),
                !account.usable(), available, account.getLinkedAt());
    }

    private void tell(UUID userId) {
        try {
            BungieAccountData data = data(userId);
            events.bungieAccountChanged(userId, data == null ? BungieAccountData.unlinked(false) : data);
        } catch (RuntimeException e) {
            log.warn("Could not tell the pages of {} about their Bungie link: {}", userId,
                    e.getClass().getSimpleName());
        }
    }

    /** Writes the link; true when it is another Bungie.net account than the one linked (or none was). */
    private boolean write(UUID userId, PendingLink pending, String refreshToken) {
        try {
            return writeOnce(userId, pending, refreshToken);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, pending, refreshToken);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("The Bungie link of {} crossed another write twice", userId);
                throw new ApiProblem(ErrorCode.CONFLICT, "The Bungie link changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, PendingLink pending, String refreshToken) {
        Instant now = clock.instant();
        Optional<BungieAccount> found = accounts.findById(userId);
        BungieAccount row = found.orElseGet(() -> new BungieAccount(userId));
        boolean changed = found.isEmpty() || !pending.membershipId().equals(row.getMembershipId());
        row.setMembershipId(pending.membershipId());
        row.setDisplayName(pending.displayName());
        row.setMembershipType(pending.membershipType());
        row.setDestinyMembershipId(pending.destinyMembershipId());
        row.setRefreshTokenEnc(encryption.encrypt(refreshToken));
        row.setRefreshExpiresAt(pending.refreshExpiresAt());
        row.setInvalidSince(null);
        if (changed) {
            row.setLinkedAt(now);
        }
        row.setUpdatedAt(now);
        accounts.save(row);
        return changed;
    }

    private static Duration parseSeconds(Object value) {
        try {
            return value == null ? REFRESH_LIFETIME : Duration.ofSeconds(Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return REFRESH_LIFETIME;
        }
    }
}
