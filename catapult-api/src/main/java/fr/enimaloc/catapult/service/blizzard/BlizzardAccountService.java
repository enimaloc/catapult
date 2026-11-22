package fr.enimaloc.catapult.service.blizzard;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.BlizzardStatsData;
import fr.enimaloc.catapult.common.dto.channel.BlizzardStatsData.WowCharacterOption;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.blizzard.BlizzardAccount;
import fr.enimaloc.catapult.domain.blizzard.BlizzardWowCharacter;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardAccountRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardWowCharacterRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A member's Blizzard settings: the region of their Battle.net account and their imported World of Warcraft
 * characters (with the one picked as main). The settings need a Battle.net link and go away with it ({@link #forget}).
 * No secret is held. Each write runs in a transaction on the row as it is now and is optimistically locked: one that
 * crosses another is redone once on the fresh row, then is a 409. Telling the owner's pages comes after the write and
 * never fails it. {@link #data} reads the database only, never Blizzard.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BlizzardAccountService {

    /** One character as the import read it. */
    public record ImportedCharacter(BlizzardRegion region, long id, String realmSlug, String realmName, String name,
                                    int level, String className, String faction) {}

    private final BlizzardAccountRepository accounts;
    private final BlizzardWowCharacterRepository characters;
    private final BattleNetFriendLinkRepository links;
    private final EnrichmentCache cache;
    private final ChannelEventPublisher events;
    private final TransactionOperations transactions;
    private final boolean available;
    private final Clock clock;

    @Autowired
    public BlizzardAccountService(BlizzardAccountRepository accounts, BlizzardWowCharacterRepository characters,
                                  BattleNetFriendLinkRepository links, EnrichmentCache cache,
                                  ChannelEventPublisher events, TransactionOperations transactions,
                                  @Value("${battlenet.client-id:}") String clientId,
                                  @Value("${battlenet.client-secret:}") String clientSecret) {
        this(accounts, characters, links, cache, events, transactions, clientId, clientSecret, Clock.systemUTC());
    }

    BlizzardAccountService(BlizzardAccountRepository accounts, BlizzardWowCharacterRepository characters,
                           BattleNetFriendLinkRepository links, EnrichmentCache cache, ChannelEventPublisher events,
                           TransactionOperations transactions, String clientId, String clientSecret, Clock clock) {
        this.accounts     = accounts;
        this.characters   = characters;
        this.links        = links;
        this.cache        = cache;
        this.events       = events;
        this.transactions = transactions;
        this.available    = clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        this.clock        = clock;
    }

    public Optional<BlizzardAccount> find(UUID userId) {
        return accounts.findById(userId);
    }

    /** Sets the region of the member's Battle.net account (any case; unknown is a 400, no Battle.net link a 404). */
    public void setRegion(UserAccount user, String region) {
        BlizzardRegion parsed = BlizzardRegion.parse(region)
                .orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown Blizzard region"));
        requireLink(user.getId());
        write(user.getId(), true, row -> row.setRegion(parsed.key()));
        done(user.getId());
    }

    /** Picks {@code characterId} of {@code region} as the member's main; it must be one of the imported ones. */
    public void setWowMain(UserAccount user, BlizzardRegion region, long characterId) {
        boolean written = write(user.getId(), false, row -> {
            boolean listed = characters.findByUserId(user.getId()).stream()
                    .anyMatch(c -> c.getRegion().equals(region.key()) && c.getCharacterId() == characterId);
            if (!listed) {
                throw new ApiProblem(ErrorCode.BAD_REQUEST, "Not one of the imported characters");
            }
            row.setWowMainRegion(region.key());
            row.setWowMainCharacterId(characterId);
        });
        if (!written) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Not one of the imported characters");
        }
        done(user.getId());
    }

    /**
     * Replaces the member's imported characters by {@code imported}. The main stays when it is still listed, else it
     * becomes the highest level (the first of them on a tie), none for an empty list. Nothing is written without a row.
     */
    public void replaceWowCharacters(UUID userId, List<ImportedCharacter> imported, Instant at) {
        Map<String, BlizzardWowCharacter> rows = new LinkedHashMap<>();
        for (ImportedCharacter c : imported) {
            BlizzardWowCharacter row = new BlizzardWowCharacter();
            row.setUserId(userId);
            row.setRegion(c.region().key());
            row.setCharacterId(c.id());
            row.setRealmSlug(c.realmSlug());
            row.setRealmName(c.realmName());
            row.setName(c.name().toLowerCase(Locale.ROOT));
            row.setDisplayName(c.name());
            row.setLevel(c.level());
            row.setClassName(c.className());
            row.setFaction(c.faction());
            rows.putIfAbsent(key(row.getRegion(), row.getCharacterId()), row);
        }
        boolean written = write(userId, false, row -> {
            characters.deleteByUserId(userId);
            characters.saveAll(rows.values());
            if (row.getWowMainRegion() == null || row.getWowMainCharacterId() == null
                    || !rows.containsKey(key(row.getWowMainRegion(), row.getWowMainCharacterId()))) {
                BlizzardWowCharacter best = null;
                for (BlizzardWowCharacter c : rows.values()) {
                    if (best == null || c.getLevel() > best.getLevel()) {
                        best = c;
                    }
                }
                row.setWowMainRegion(best == null ? null : best.getRegion());
                row.setWowMainCharacterId(best == null ? null : best.getCharacterId());
            }
            row.setWowImportedAt(at);
        });
        if (written) {
            done(userId);
        }
    }

    /** Forgets the imported characters and the main (the region stays). */
    public void deleteWowCharacters(UserAccount user) {
        boolean written = write(user.getId(), false, row -> {
            characters.deleteByUserId(user.getId());
            row.setWowMainRegion(null);
            row.setWowMainCharacterId(null);
            row.setWowImportedAt(null);
        });
        if (written) {
            done(user.getId());
        }
    }

    /**
     * Deletes everything held for the member (their Battle.net link is gone) and what was cached of their stats.
     * Idempotent and never an optimistic-lock loser: bulk deletes, no read-modify-write.
     */
    public void forget(UUID userId) {
        transactions.executeWithoutResult(status -> {
            characters.deleteByUserId(userId);
            accounts.purgeByUserId(userId);
        });
        cache.evictUser(userId);
        publish(userId);
    }

    /** The settings card's data; built from the database only, never calling Blizzard. */
    public BlizzardStatsData data(UserAccount user) {
        return data(user.getId());
    }

    private BlizzardStatsData data(UUID userId) {
        boolean linked = activeLink(userId).isPresent();
        Optional<BlizzardAccount> row = accounts.findById(userId);
        List<WowCharacterOption> options = characters.findByUserId(userId).stream()
                .map(c -> new WowCharacterOption(key(c.getRegion(), c.getCharacterId()), label(c))).toList();
        String main = row.filter(r -> r.getWowMainRegion() != null && r.getWowMainCharacterId() != null)
                .map(r -> key(r.getWowMainRegion(), r.getWowMainCharacterId())).orElse(null);
        return new BlizzardStatsData(linked, row.map(BlizzardAccount::getRegion).orElse(null),
                row.map(r -> r.getWowImportedAt() != null).orElse(false), options, main, available);
    }

    /** The member's Battle.net link, unless it was removed (such a link is a leftover, not a link). */
    private Optional<BattleNetFriendLink> activeLink(UUID userId) {
        return links.findByUserId(userId).filter(link -> link.getStatus() != BattleNetFriendLink.Status.REMOVED);
    }

    private void requireLink(UUID userId) {
        if (activeLink(userId).isEmpty()) {
            throw new ApiProblem(ErrorCode.NOT_FOUND, "Battle.net is not linked");
        }
    }

    private static String key(String region, long characterId) {
        return region + ":" + characterId;
    }

    private static String label(BlizzardWowCharacter c) {
        return c.getDisplayName() + " — " + c.getRealmName() + " (niv. " + c.getLevel() + ", "
                + c.getClassName() + ")";
    }

    /** What follows a write: the cached stats were read with the old settings; the owner's pages are told. */
    private void done(UUID userId) {
        cache.evictUser(userId);
        publish(userId);
    }

    private void publish(UUID userId) {
        try {
            events.blizzardAccountChanged(userId, data(userId));
        } catch (RuntimeException e) {
            // The settings are saved: a page that is not told refreshes on its next load.
            log.warn("Blizzard settings of {} saved but not pushed: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Applies {@code change} to the member's row as it is now ({@code create}: a missing row is started), in one
     * transaction. A write that crosses another one is redone once on the fresh row, then is a 409.
     *
     * @return false when there was no row and {@code create} is false: nothing was written
     */
    private boolean write(UUID userId, boolean create, Consumer<BlizzardAccount> change) {
        try {
            return writeOnce(userId, create, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, create, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("Blizzard settings of {} crossed another write twice: {}", userId,
                        again.getClass().getSimpleName());
                throw new ApiProblem(ErrorCode.CONFLICT, "The Blizzard settings changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, boolean create, Consumer<BlizzardAccount> change) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (create) {
                // The link may have been removed since the caller checked: no row for a member without a link
                requireLink(userId);
            }
            Optional<BlizzardAccount> found = accounts.findById(userId);
            if (found.isEmpty() && !create) {
                return false;
            }
            BlizzardAccount row = found.orElseGet(() -> new BlizzardAccount(userId));
            change.accept(row);
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
            return true;
        }));
    }
}
