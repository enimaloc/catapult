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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The members' Bungie access tokens: kept in memory only (one hour), renewed from the stored refresh token, which
 * Bungie replaces on every renewal (the new one is written back, encrypted). Renewing only happens on the active
 * instance ({@link SiteRole}): two instances renewing the same rotating token would retire each other's. A refresh
 * token Bungie refuses, or one that comes back for another Bungie.net account than the linked one, marks the link
 * invalid: the member has to link again. No token is ever logged.
 */
@Slf4j
@Service
public class BungieTokenService {

    /** A token this close to its end is renewed first. */
    static final Duration RENEW_BEFORE = Duration.ofMinutes(1);

    /**
     * Published once a link was marked invalid (a refused refresh): the card asks to link again. An event, not a
     * call, so that the account service (which evicts tokens here) does not depend on this one in a cycle.
     */
    public record LinkInvalidated(UUID userId) {
    }

    private record Held(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "Held[expiresAt=" + expiresAt + "]";
        }
    }

    private final BungieAccountRepository accounts;
    private final BungieClient client;
    private final TokenEncryptionService encryption;
    private final SiteRole siteRole;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

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

    /**
     * An access token of the member's link; empty when there is no usable link (none, or refused just now).
     *
     * @throws BungieUnavailableException when it has to be renewed and cannot be now (Bungie down, standby instance)
     */
    public Optional<String> accessToken(UUID userId) {
        Held known = held.get(userId);
        if (fresh(known)) {
            return Optional.of(known.token());
        }
        synchronized (locks.computeIfAbsent(userId, id -> new Object())) {
            known = held.get(userId);
            if (fresh(known)) {
                return Optional.of(known.token());
            }
            Optional<BungieAccount> row = accounts.findById(userId).filter(BungieAccount::usable);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            try {
                siteRole.requireActive("bungie token refresh");
            } catch (StandbyException e) {
                throw new BungieUnavailableException("the Bungie token is only renewed on the active instance");
            }
            String refreshToken;
            try {
                refreshToken = encryption.decrypt(row.get().getRefreshTokenEnc());
            } catch (RuntimeException e) {
                log.warn("The Bungie refresh token of {} cannot be decrypted", userId);
                throw new BungieUnavailableException("the Bungie refresh token cannot be read");
            }
            TokenGrant grant;
            try {
                grant = client.refresh(refreshToken);
            } catch (BungieExceptions.RefreshRefused e) {
                log.info("Bungie refused the refresh token of {}: the link has to be made again", userId);
                invalidate(userId);
                return Optional.empty();
            }
            if (!row.get().getMembershipId().equals(grant.membershipId())) {
                // Not the linked account: never read (nor keep) another account's tokens under this member
                log.warn("The Bungie refresh of {} came back for another Bungie.net account", userId);
                invalidate(userId);
                return Optional.empty();
            }
            boolean kept = write(userId, account -> {
                account.setRefreshTokenEnc(encryption.encrypt(grant.refreshToken()));
                account.setRefreshExpiresAt(grant.refreshExpiresAt());
            });
            if (!kept) {
                // Unlinked meanwhile: the new tokens are dropped
                return Optional.empty();
            }
            held.put(userId, new Held(grant.accessToken(), grant.expiresAt()));
            return Optional.of(grant.accessToken());
        }
    }

    /** Forgets {@code token} (Bungie refused it): the next call renews it. */
    public void refused(UUID userId, String token) {
        held.computeIfPresent(userId, (id, known) -> known.token().equals(token) ? null : known);
    }

    /** Forgets the member's access token (unlinked, linked again). */
    public void evict(UUID userId) {
        held.remove(userId);
    }

    private boolean fresh(Held known) {
        return known != null && known.expiresAt().isAfter(clock.instant().plus(RENEW_BEFORE));
    }

    /** Marks the link invalid and drops its refresh token. */
    private void invalidate(UUID userId) {
        held.remove(userId);
        if (write(userId, account -> {
            account.setRefreshTokenEnc(null);
            account.setInvalidSince(clock.instant());
        })) {
            events.publishEvent(new LinkInvalidated(userId));
        }
    }

    /** Applies {@code change} to the row as it is now; redone once on a crossed write. False when there is no row. */
    private boolean write(UUID userId, Consumer<BungieAccount> change) {
        try {
            return writeOnce(userId, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("The Bungie link of {} crossed another write twice", userId);
                throw new BungieUnavailableException("the Bungie link changed meanwhile");
            }
        }
    }

    private boolean writeOnce(UUID userId, Consumer<BungieAccount> change) {
        Optional<BungieAccount> row = accounts.findById(userId);
        if (row.isEmpty()) {
            return false;
        }
        change.accept(row.get());
        row.get().setUpdatedAt(clock.instant());
        accounts.save(row.get());
        return true;
    }
}
