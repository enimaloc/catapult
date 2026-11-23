package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import fr.enimaloc.catapult.repository.bungie.BungieAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.TokenGrant;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.StandbyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The members' Bungie access tokens: kept in memory only (one hour), renewed from the stored refresh token, which
 * Bungie replaces on every renewal (the new one is written back, encrypted). Renewing only happens on the active
 * instance ({@link SiteRole}): two instances renewing the same rotating token would retire each other's.
 *
 * <p>Every write is a compare-and-set on the row the renewal read (same version, same refresh token, same
 * account): a link made again, or renewed elsewhere, while Bungie was being asked is never overwritten with the
 * former link's tokens, and a refusal of a former token never invalidates the fresh link. The link service takes
 * the same per-member lock ({@link #locked}) to write a new link. A refresh token Bungie refuses, or one that comes
 * back for another Bungie.net account than the linked one, marks that member's link invalid (the encrypted token is
 * kept until the unlink); when several members' refreshes are refused within a short while, the application itself
 * is suspect and the refusal is an outage instead, nobody's link is touched. No token is ever logged.
 */
@Slf4j
@Service
public class BungieTokenService {

    /** A token this close to its end is renewed first. */
    static final Duration RENEW_BEFORE = Duration.ofMinutes(1);
    /** A token Bungie refuses this soon after it was renewed is not renewed again before this long (M8). */
    static final Duration REFUSED_COOLDOWN = Duration.ofMinutes(5);
    /** This many members' refresh tokens refused within {@link #SWEEP_WINDOW}: the application is suspect. */
    static final int SWEEP_THRESHOLD = 3;
    static final Duration SWEEP_WINDOW = Duration.ofMinutes(10);

    /**
     * Published once a link was marked invalid (a refused refresh): the card asks to link again. An event, not a
     * call, so that the account service (which evicts tokens here) does not depend on this one in a cycle.
     */
    public record LinkInvalidated(UUID userId) {
    }

    private record Held(String token, Instant expiresAt, Instant obtainedAt) {
        @Override
        public String toString() {
            return "Held[expiresAt=" + expiresAt + "]";
        }
    }

    /** A rotated refresh token the database refused to take: kept here and written again on the next call. */
    private record Unsaved(Long expectedVersion, String expectedEnc, String refreshEnc, Instant refreshExpiresAt,
                           String membershipId) {
        @Override
        public String toString() {
            return "Unsaved[membershipId=" + membershipId + "]";
        }
    }

    private final BungieAccountRepository accounts;
    private final BungieClient client;
    private final TokenEncryptionService encryption;
    private final SiteRole siteRole;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    private final Map<UUID, Unsaved> unsaved = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> cooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final Deque<Instant> refusals = new ArrayDeque<>();

    @Autowired
    public BungieTokenService(BungieAccountRepository accounts, BungieClient client, TokenEncryptionService encryption,
                              SiteRole siteRole, ApplicationEventPublisher events) {
        this(accounts, client, encryption, siteRole, events, Clock.systemUTC());
    }

    BungieTokenService(BungieAccountRepository accounts, BungieClient client, TokenEncryptionService encryption,
                       SiteRole siteRole, ApplicationEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.client = client;
        this.encryption = encryption;
        this.siteRole = siteRole;
        this.events = events;
        this.clock = clock;
    }

    /** {@code work} under the member's lock: no renewal of theirs runs meanwhile. */
    public <T> T locked(UUID userId, Supplier<T> work) {
        synchronized (lock(userId)) {
            return work.get();
        }
    }

    /**
     * An access token of the member's link; empty when there is no usable link (none, or refused just now). One
     * renewal at most per call.
     *
     * @throws BungieUnavailableException when it has to be renewed and cannot be now (Bungie down, standby
     *         instance, refused just after a renewal, the link changed meanwhile)
     */
    public Optional<String> accessToken(UUID userId) {
        Held known = held.get(userId);
        if (fresh(known) && !unsaved.containsKey(userId)) {
            return Optional.of(known.token());
        }
        synchronized (lock(userId)) {
            retryUnsaved(userId);
            known = held.get(userId);
            if (fresh(known)) {
                return Optional.of(known.token());
            }
            Instant waitUntil = cooldown.get(userId);
            if (waitUntil != null && waitUntil.isAfter(clock.instant())) {
                throw new BungieUnavailableException("Bungie refused a just renewed token, not renewing yet");
            }
            Optional<BungieAccount> row = accounts.findById(userId).filter(BungieAccount::usable);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            BungieAccount read = row.get();
            try {
                siteRole.requireActive("bungie token refresh");
            } catch (StandbyException e) {
                throw new BungieUnavailableException("the Bungie token is only renewed on the active instance");
            }
            Unsaved pending = unsaved.get(userId);
            String refreshToken;
            try {
                refreshToken = encryption.decrypt(pending != null ? pending.refreshEnc() : read.getRefreshTokenEnc());
            } catch (RuntimeException e) {
                log.warn("The Bungie refresh token of {} cannot be decrypted", userId);
                throw new BungieUnavailableException("the Bungie refresh token cannot be read");
            }
            TokenGrant grant;
            try {
                grant = client.refresh(refreshToken);
            } catch (BungieExceptions.RefreshRefused e) {
                refused(userId, read);
                return Optional.empty();
            }
            if (!read.getMembershipId().equals(grant.membershipId())) {
                // Not the linked account: never read (nor keep) another account's tokens under this member
                log.warn("The Bungie refresh of {} came back for another Bungie.net account", userId);
                invalidate(userId, read);
                return Optional.empty();
            }
            unsaved.remove(userId);
            Unsaved rotated = new Unsaved(read.getVersion(), read.getRefreshTokenEnc(),
                    encryption.encrypt(grant.refreshToken()), grant.refreshExpiresAt(), read.getMembershipId());
            Boolean written = storeRotated(userId, rotated);
            if (written == null) {
                // The database refused it: the access token is good for an hour, the refresh token waits here
                held.put(userId, new Held(grant.accessToken(), grant.expiresAt(), clock.instant()));
                return Optional.of(grant.accessToken());
            }
            if (!written) {
                // Unlinked or linked again meanwhile: these tokens are the former link's, dropped
                throw new BungieUnavailableException("the Bungie link changed during the renewal");
            }
            held.put(userId, new Held(grant.accessToken(), grant.expiresAt(), clock.instant()));
            return Optional.of(grant.accessToken());
        }
    }

    /**
     * Forgets {@code token} (Bungie refused it): the next call renews it, unless it was renewed just before, then not
     * before {@link #REFUSED_COOLDOWN} (a key of another application makes Bungie refuse every token).
     */
    public void refused(UUID userId, String token) {
        Held known = held.get(userId);
        if (known != null && known.token().equals(token)) {
            held.remove(userId, known);
            if (known.obtainedAt().plus(REFUSED_COOLDOWN).isAfter(clock.instant())) {
                cooldown.put(userId, clock.instant().plus(REFUSED_COOLDOWN));
            }
        }
    }

    /** Holds the access token a fresh link came with (valid an hour): the first read does not renew at once. */
    public void seed(UUID userId, String accessToken, Instant expiresAt) {
        if (accessToken != null && expiresAt != null) {
            held.put(userId, new Held(accessToken, expiresAt, clock.instant()));
        }
    }

    /** Forgets everything held for the member (unlinked, linked again, account deleted). */
    public void evict(UUID userId) {
        held.remove(userId);
        unsaved.remove(userId);
        cooldown.remove(userId);
        locks.remove(userId);
    }

    private Object lock(UUID userId) {
        return locks.computeIfAbsent(userId, id -> new Object());
    }

    private boolean fresh(Held known) {
        return known != null && known.expiresAt().isAfter(clock.instant().plus(RENEW_BEFORE));
    }

    /** A refused refresh token: this member's link only, or nobody's when the application itself looks wrong. */
    private void refused(UUID userId, BungieAccount read) {
        if (applicationSuspect()) {
            log.warn("Bungie refuses several members' refresh tokens: the application credentials look wrong, "
                    + "no link is invalidated");
            throw new BungieUnavailableException("Bungie refuses the refresh tokens of several members");
        }
        log.info("Bungie refused the refresh token of {}: the link has to be made again", userId);
        invalidate(userId, read);
    }

    private synchronized boolean applicationSuspect() {
        Instant now = clock.instant();
        while (!refusals.isEmpty() && refusals.peekFirst().plus(SWEEP_WINDOW).isBefore(now)) {
            refusals.removeFirst();
        }
        if (refusals.size() >= SWEEP_THRESHOLD) {
            return true;
        }
        refusals.addLast(now);
        return false;
    }

    /** Marks the link invalid, only if it still is the one that was read; the encrypted token stays until unlink. */
    private void invalidate(UUID userId, BungieAccount read) {
        held.remove(userId);
        unsaved.remove(userId);
        Boolean done = write(userId, read.getVersion(), read.getRefreshTokenEnc(), read.getMembershipId(),
                account -> account.setInvalidSince(clock.instant()));
        if (Boolean.TRUE.equals(done)) {
            events.publishEvent(new LinkInvalidated(userId));
        }
    }

    /** True when written, false when the row is gone or changed, null when the database refused (kept in memory). */
    private Boolean storeRotated(UUID userId, Unsaved rotated) {
        Boolean written = write(userId, rotated.expectedVersion(), rotated.expectedEnc(), rotated.membershipId(),
                account -> {
                    account.setRefreshTokenEnc(rotated.refreshEnc());
                    account.setRefreshExpiresAt(rotated.refreshExpiresAt());
                });
        if (written == null) {
            log.warn("The rotated Bungie refresh token of {} could not be stored: kept in memory, written again on "
                    + "the next call", userId);
            unsaved.put(userId, rotated);
        }
        return written;
    }

    private void retryUnsaved(UUID userId) {
        Unsaved pending = unsaved.get(userId);
        if (pending != null) {
            Boolean written = write(userId, pending.expectedVersion(), pending.expectedEnc(), pending.membershipId(),
                    account -> {
                        account.setRefreshTokenEnc(pending.refreshEnc());
                        account.setRefreshExpiresAt(pending.refreshExpiresAt());
                    });
            if (written != null) {
                unsaved.remove(userId, pending);
                if (!written) {
                    held.remove(userId);
                }
            }
        }
    }

    /**
     * Applies {@code change} to the row when it still is the one read (version, refresh token, account): true when
     * written, false when the row is gone or changed (nothing written), null when the database refused the write.
     */
    private Boolean write(UUID userId, Long version, String refreshEnc, String membershipId,
                          Consumer<BungieAccount> change) {
        try {
            Optional<BungieAccount> row = accounts.findById(userId);
            if (row.isEmpty() || !Objects.equals(row.get().getVersion(), version)
                    || !Objects.equals(row.get().getRefreshTokenEnc(), refreshEnc)
                    || !Objects.equals(row.get().getMembershipId(), membershipId)) {
                return false;
            }
            change.accept(row.get());
            row.get().setUpdatedAt(clock.instant());
            accounts.save(row.get());
            return true;
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException crossed) {
            // Another write won since the read: this one is stale
            return false;
        } catch (DataAccessException e) {
            log.warn("The Bungie link of {} could not be written: {}", userId, e.getClass().getSimpleName());
            return null;
        }
    }
}
