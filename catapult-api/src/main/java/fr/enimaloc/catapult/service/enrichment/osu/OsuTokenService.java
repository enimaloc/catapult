package fr.enimaloc.catapult.service.enrichment.osu;

import fr.enimaloc.catapult.domain.osu.OsuAccount;
import fr.enimaloc.catapult.repository.osu.OsuAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.TokenGrant;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.StandbyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * The members' osu! access tokens: kept in memory only (24 h at osu!), renewed from the stored refresh token, which
 * osu! replaces on every renewal (the new one is written back, encrypted). Renewing only happens on the active
 * instance ({@link SiteRole}): two instances renewing the same rotating token would retire each other's.
 *
 * <p>osu!'s token answer names no account, so a renewed token is checked with {@code GET /me}: one that reads another
 * osu! account than the linked one is never used, and the link is marked invalid. Every write is a compare-and-set on
 * the row the renewal read (same version, same refresh token, same osu! account): a link made again, or renewed
 * elsewhere, while osu! was being asked is never overwritten with the former link's tokens, and a refusal of a former
 * token never invalidates the fresh link. The link service takes the same per-member lock ({@link #locked}), which is
 * never removed, to write a new link. A refresh token osu! refuses marks that member's link invalid (the encrypted
 * token is kept until the unlink); when several members' refreshes are refused within a short while, the
 * application itself is suspect and the refusal is an outage instead, nobody's link is touched. No token is ever
 * logged.
 */
@Slf4j
@Service
public class OsuTokenService {

    /** A token this close to its end is renewed first. */
    static final Duration RENEW_BEFORE = Duration.ofMinutes(5);
    /** A token osu! refuses this soon after it was renewed is not renewed again before this long. */
    static final Duration REFUSED_COOLDOWN = Duration.ofMinutes(5);
    /** This many members' refresh tokens refused within {@link #SWEEP_WINDOW}: the application is suspect. */
    static final int SWEEP_THRESHOLD = 3;
    static final Duration SWEEP_WINDOW = Duration.ofMinutes(10);
    /** Once suspect, the application stays so this long after the last refusal: no link is invalidated meanwhile. */
    static final Duration SUSPECT_FOR = Duration.ofHours(1);

    /**
     * Published once a link was marked invalid (a refused refresh, another account): the card asks to link again. An
     * event, not a call, so that the account service (which evicts tokens here) does not depend on this one in a
     * cycle.
     */
    public record LinkInvalidated(UUID userId) {
    }

    /** A held access token and the link it belongs to: the osu! account and when that account was linked. */
    private record Held(String token, Instant expiresAt, Instant obtainedAt, Long osuUserId, Instant linkedAt) {

        /** Whether this token is the one of {@code row}'s link (not of a link made again elsewhere). */
        boolean of(OsuAccount row) {
            return Objects.equals(osuUserId, row.getOsuUserId()) && Objects.equals(linkedAt, row.getLinkedAt());
        }

        @Override
        public String toString() {
            return "Held[expiresAt=" + expiresAt + "]";
        }
    }

    /** A rotated refresh token the database refused to take: kept here and written again on the next call. */
    private record Unsaved(Long expectedVersion, String expectedEnc, String refreshEnc, Long osuUserId) {
        @Override
        public String toString() {
            return "Unsaved[osuUserId=" + osuUserId + "]";
        }
    }

    /** How a write went: {@code row} is the row as written (WRITTEN only). */
    private enum Outcome { WRITTEN, STALE, FAILED }

    private record Write(Outcome outcome, OsuAccount row) {
    }

    private final OsuAccountRepository accounts;
    private final OsuClient client;
    private final TokenEncryptionService encryption;
    private final SiteRole siteRole;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final OsuPlayerPacer pacer;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    private final Map<UUID, Unsaved> unsaved = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> cooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final Deque<Instant> refusals = new ArrayDeque<>();
    private Instant suspectUntil;

    @Autowired
    public OsuTokenService(OsuAccountRepository accounts, OsuClient client, TokenEncryptionService encryption,
                           SiteRole siteRole, ApplicationEventPublisher events, OsuPlayerPacer pacer) {
        this(accounts, client, encryption, siteRole, events, Clock.systemUTC(), pacer);
    }

    OsuTokenService(OsuAccountRepository accounts, OsuClient client, TokenEncryptionService encryption,
                    SiteRole siteRole, ApplicationEventPublisher events, Clock clock) {
        this(accounts, client, encryption, siteRole, events, clock, new OsuPlayerPacer(clock));
    }

    OsuTokenService(OsuAccountRepository accounts, OsuClient client, TokenEncryptionService encryption,
                    SiteRole siteRole, ApplicationEventPublisher events, Clock clock, OsuPlayerPacer pacer) {
        this.pacer = pacer;
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
     * An access token of the member's link; empty when there is no usable link (none, refused just now, another
     * account). One renewal at most per call.
     *
     * @throws OsuUnavailableException when it has to be renewed and cannot be now (osu! down, standby instance,
     *         refused just after a renewal, the link changed meanwhile, the renewed token could not be checked)
     */
    public Optional<String> accessToken(UUID userId) {
        // The row is read first: a held token only serves the link it came with (a link made again on another
        // instance, or refused since, is never answered with the former link's token)
        Optional<OsuAccount> current = accounts.findById(userId).filter(OsuAccount::usable);
        if (current.isEmpty()) {
            held.remove(userId);
            return Optional.empty();
        }
        Held known = held.get(userId);
        if (fresh(known) && known.of(current.get()) && !unsaved.containsKey(userId)) {
            return Optional.of(known.token());
        }
        synchronized (lock(userId)) {
            retryUnsaved(userId);
            Optional<OsuAccount> row = accounts.findById(userId).filter(OsuAccount::usable);
            if (row.isEmpty()) {
                held.remove(userId);
                return Optional.empty();
            }
            OsuAccount read = row.get();
            known = held.get(userId);
            if (known != null && !known.of(read)) {
                held.remove(userId, known);
                known = null;
            }
            if (fresh(known)) {
                return Optional.of(known.token());
            }
            Instant waitUntil = cooldown.get(userId);
            if (waitUntil != null && waitUntil.isAfter(clock.instant())) {
                throw new OsuUnavailableException("osu! refused a just renewed token, not renewing yet");
            }
            try {
                siteRole.requireActive("osu token refresh");
            } catch (StandbyException e) {
                throw new OsuUnavailableException("the osu! token is only renewed on the active instance");
            }
            Unsaved pending = unsaved.get(userId);
            String refreshToken;
            try {
                if (pending != null && !(Objects.equals(pending.expectedVersion(), read.getVersion())
                        && sameSecret(pending.expectedEnc(), read.getRefreshTokenEnc()))) {
                    // The row changed since this token was rotated (linked again elsewhere): it is not this row's
                    unsaved.remove(userId);
                    pending = null;
                }
                refreshToken = encryption.decrypt(pending != null ? pending.refreshEnc() : read.getRefreshTokenEnc());
            } catch (RuntimeException e) {
                log.warn("The osu! refresh token of {} cannot be decrypted", userId);
                throw new OsuUnavailableException("the osu! refresh token cannot be read");
            }
            TokenGrant grant;
            try {
                grant = client.refresh(refreshToken);
            } catch (OsuExceptions.RefreshRefused e) {
                refused(userId, read);
                return Optional.empty();
            }
            unsaved.remove(userId);
            Unsaved rotated = new Unsaved(read.getVersion(), read.getRefreshTokenEnc(),
                    encryption.encrypt(grant.refreshToken()), read.getOsuUserId());
            Write written = storeRotated(userId, rotated);
            if (written.outcome() == Outcome.STALE) {
                // Unlinked or linked again meanwhile: these tokens are the former link's, dropped
                throw new OsuUnavailableException("the osu! link changed during the renewal");
            }
            // The renewed token is checked before it is used: never another account's data under this member
            long reads;
            try {
                reads = client.me(grant.accessToken()).id();
            } catch (OsuExceptions.AuthRefused e) {
                throw new OsuUnavailableException("osu! refused the token it just renewed");
            }
            if (reads != read.getOsuUserId()) {
                log.warn("The osu! refresh of {} came back for another osu! account", userId);
                if (written.outcome() == Outcome.WRITTEN) {
                    invalidate(userId, written.row());
                } else {
                    unsaved.remove(userId);
                    invalidate(userId, read);
                }
                return Optional.empty();
            }
            held.put(userId, new Held(grant.accessToken(), grant.expiresAt(), clock.instant(), read.getOsuUserId(),
                    read.getLinkedAt()));
            return Optional.of(grant.accessToken());
        }
    }

    /**
     * Forgets {@code token} (osu! refused it): the next call renews it, unless it was renewed just before, then not
     * before {@link #REFUSED_COOLDOWN}.
     */
    public void refused(UUID userId, String token) {
        Held known = held.get(userId);
        if (known != null && sameSecret(known.token(), token)) {
            held.remove(userId, known);
            if (known.obtainedAt().plus(REFUSED_COOLDOWN).isAfter(clock.instant())) {
                cooldown.put(userId, clock.instant().plus(REFUSED_COOLDOWN));
            }
        }
    }

    /**
     * Holds the access token a fresh link came with (valid 24 h), for the link of osu! account {@code osuUserId}
     * made at {@code linkedAt}: the first read does not renew at once.
     */
    public void seed(UUID userId, long osuUserId, Instant linkedAt, String accessToken, Instant expiresAt) {
        if (accessToken != null && expiresAt != null) {
            held.put(userId, new Held(accessToken, expiresAt, clock.instant(), osuUserId, linkedAt));
        }
    }

    /** Forgets everything held for the member (unlinked, linked again, account deleted), their read pacing too. */
    public void evict(UUID userId) {
        held.remove(userId);
        unsaved.remove(userId);
        cooldown.remove(userId);
        pacer.forget(userId);
        // The lock entry stays: evict runs under it, and a fresh one would let two renewals race on one token
    }

    /** Two secrets compared in constant time; two nulls are the same. */
    private static boolean sameSecret(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private Object lock(UUID userId) {
        return locks.computeIfAbsent(userId, id -> new Object());
    }

    private boolean fresh(Held known) {
        return known != null && known.expiresAt().isAfter(clock.instant().plus(RENEW_BEFORE));
    }

    /** A refused refresh token: this member's link only, or nobody's when the application itself looks wrong. */
    private void refused(UUID userId, OsuAccount read) {
        if (applicationSuspect()) {
            log.warn("osu! refuses several members' refresh tokens: the application credentials look wrong, "
                    + "no link is invalidated");
            throw new OsuUnavailableException("osu! refuses the refresh tokens of several members");
        }
        log.info("osu! refused the refresh token of {}: the link has to be made again", userId);
        invalidate(userId, read);
    }

    private synchronized boolean applicationSuspect() {
        Instant now = clock.instant();
        if (suspectUntil != null && suspectUntil.isAfter(now)) {
            suspectUntil = now.plus(SUSPECT_FOR);
            return true;
        }
        while (!refusals.isEmpty() && refusals.peekFirst().plus(SWEEP_WINDOW).isBefore(now)) {
            refusals.removeFirst();
        }
        if (refusals.size() >= SWEEP_THRESHOLD) {
            suspectUntil = now.plus(SUSPECT_FOR);
            return true;
        }
        refusals.addLast(now);
        return false;
    }

    /** Marks the link invalid, only if it still is the one that was read; the encrypted token stays until unlink. */
    private void invalidate(UUID userId, OsuAccount read) {
        held.remove(userId);
        unsaved.remove(userId);
        Write done = write(userId, read.getVersion(), read.getRefreshTokenEnc(), read.getOsuUserId(),
                account -> account.setInvalidSince(clock.instant()));
        if (done.outcome() == Outcome.WRITTEN) {
            events.publishEvent(new LinkInvalidated(userId));
        }
    }

    private Write storeRotated(UUID userId, Unsaved rotated) {
        Write written = write(userId, rotated.expectedVersion(), rotated.expectedEnc(), rotated.osuUserId(),
                account -> account.setRefreshTokenEnc(rotated.refreshEnc()));
        if (written.outcome() == Outcome.FAILED) {
            log.warn("The rotated osu! refresh token of {} could not be stored: kept in memory, written again on "
                    + "the next call", userId);
            unsaved.put(userId, rotated);
        }
        return written;
    }

    private void retryUnsaved(UUID userId) {
        Unsaved pending = unsaved.get(userId);
        if (pending != null) {
            Write written = write(userId, pending.expectedVersion(), pending.expectedEnc(), pending.osuUserId(),
                    account -> account.setRefreshTokenEnc(pending.refreshEnc()));
            if (written.outcome() != Outcome.FAILED) {
                unsaved.remove(userId, pending);
                if (written.outcome() == Outcome.STALE) {
                    held.remove(userId);
                }
            }
        }
    }

    /**
     * Applies {@code change} to the row when it still is the one read (version, refresh token, account): WRITTEN
     * with the row as saved, STALE when the row is gone or changed (nothing written), FAILED when the database
     * refused the write.
     */
    private Write write(UUID userId, Long version, String refreshEnc, Long osuUserId, Consumer<OsuAccount> change) {
        try {
            Optional<OsuAccount> row = accounts.findById(userId);
            if (row.isEmpty() || !Objects.equals(row.get().getVersion(), version)
                    || !sameSecret(row.get().getRefreshTokenEnc(), refreshEnc)
                    || !Objects.equals(row.get().getOsuUserId(), osuUserId)) {
                return new Write(Outcome.STALE, null);
            }
            change.accept(row.get());
            row.get().setUpdatedAt(clock.instant());
            OsuAccount saved = accounts.saveAndFlush(row.get());
            return new Write(Outcome.WRITTEN, saved == null ? row.get() : saved);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException crossed) {
            // Another write won since the read: this one is stale
            return new Write(Outcome.STALE, null);
        } catch (DataAccessException e) {
            log.warn("The osu! link of {} could not be written: {}", userId, e.getClass().getSimpleName());
            return new Write(Outcome.FAILED, null);
        }
    }
}
