package fr.enimaloc.catapult.service.riot;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.RiotData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.riot.RiotAccount;
import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.repository.riot.RiotAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.riot.RiotClient;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.RiotAccountRef;
import fr.enimaloc.catapult.service.enrichment.riot.RiotNotFoundException;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A member's Riot settings: the Riot ID they declare ({@code gameName#tagLine}) and their League of Legends platform.
 * The Riot ID is checked with Riot (account-v1, which gives the PUUID later calls use) before anything is written: an
 * unknown Riot ID is a 400; Riot out of reach, the source off or no key of the pool for the platform's cluster a 503.
 * As for RetroAchievements, nothing stops a member from declaring someone else's Riot ID: the stats are only ever
 * served to the member themselves. Each write re-reads the row and is optimistically locked: one that crosses another
 * is redone once on the fresh row, then answers 409. A changed or removed Riot ID forgets what the Riot sources cached
 * for the member. Telling the owner's pages comes after the write and never fails it.
 */
@Slf4j
@Service
public class RiotAccountService {

    /** Riot's game names: 3 to 16 letters, digits, spaces, dots or underscores. */
    static final Pattern GAME_NAME = Pattern.compile("[\\p{L}\\p{N} ._]{3,16}");
    /** Riot's taglines: 3 to 5 letters or digits. */
    static final Pattern TAG_LINE = Pattern.compile("[\\p{L}\\p{N}]{3,5}");
    static final List<EnrichmentSource> SOURCES = Arrays.stream(EnrichmentSource.values())
            .filter(s -> s.personal() && s.key().startsWith("riot-")).toList();

    private final RiotAccountRepository accounts;
    private final RiotClient client;
    private final RiotApiKeyRotator keys;
    private final EnrichmentProperties properties;
    private final EnrichmentCache cache;
    private final ChannelEventPublisher events;
    private final Clock clock;

    @Autowired
    public RiotAccountService(RiotAccountRepository accounts, RiotClient client, RiotApiKeyRotator keys,
                              EnrichmentProperties properties, EnrichmentCache cache, ChannelEventPublisher events) {
        this(accounts, client, keys, properties, cache, events, Clock.systemUTC());
    }

    RiotAccountService(RiotAccountRepository accounts, RiotClient client, RiotApiKeyRotator keys,
                       EnrichmentProperties properties, EnrichmentCache cache, ChannelEventPublisher events,
                       Clock clock) {
        this.accounts = accounts;
        this.client = client;
        this.keys = keys;
        this.properties = properties;
        this.cache = cache;
        this.events = events;
        this.clock = clock;
    }

    /** Declares {@code riotId} ({@code gameName#tagLine}, trimmed) on {@code platform}, once Riot knows it. */
    public void save(UserAccount user, String riotId, String platform) {
        String raw = riotId == null ? "" : riotId.strip();
        int hash = raw.lastIndexOf('#');
        String name = hash < 0 ? "" : raw.substring(0, hash).strip();
        String tag = hash < 0 ? "" : raw.substring(hash + 1).strip();
        if (!GAME_NAME.matcher(name).matches() || !TAG_LINE.matcher(tag).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid Riot ID (expected gameName#tagLine)");
        }
        RiotPlatform on = RiotPlatform.parse(platform)
                .orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown League of Legends platform"));
        if (!properties.riotEnabled() || !keys.serves(on.cluster())) {
            throw unreachable();
        }
        RiotAccountRef found;
        try {
            found = client.accountByRiotId(on.cluster(), name, tag);
        } catch (RiotNotFoundException e) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown Riot ID");
        } catch (RuntimeException e) {
            log.warn("Riot ID check failed: {}", e.getClass().getSimpleName());
            throw unreachable();
        }
        boolean[] changed = {false};
        write(user.getId(), true, row -> {
            changed[0] = !found.puuid().equals(row.getPuuid()) || !on.key().equals(row.getPlatform());
            row.setGameName(found.gameName());
            row.setTagLine(found.tagLine());
            row.setPuuid(found.puuid());
            row.setPuuidKeyId(found.keyId());
            row.setPlatform(on.key());
            row.setRegion(on.cluster().key());
            return true;
        });
        if (changed[0]) {
            cache.evictUser(user.getId(), SOURCES);
        }
        publish(user.getId());
    }

    /**
     * Resolves the member's PUUID again from their declared Riot ID, with a key of the pool now (the one that
     * resolved it left the pool, or Riot can no longer decrypt it), and stores it with that key's id; what the Riot
     * sources cached for the member is forgotten. Empty when there is no row any more, or Riot no longer knows the
     * Riot ID (the row is kept: the member is told {@code not_found}).
     *
     * @throws RuntimeException when Riot cannot be asked (an outage, for the caller)
     */
    public Optional<RiotAccount> reresolve(UUID userId) {
        Optional<RiotAccount> row = accounts.findById(userId);
        Optional<RiotPlatform> on = row.flatMap(r -> RiotPlatform.parse(r.getPlatform()));
        if (row.isEmpty() || on.isEmpty()) {
            return Optional.empty();
        }
        RiotAccountRef found;
        try {
            found = client.accountByRiotId(on.get().cluster(), row.get().getGameName(), row.get().getTagLine());
        } catch (RiotNotFoundException e) {
            return Optional.empty();
        }
        write(userId, false, current -> {
            current.setGameName(found.gameName());
            current.setTagLine(found.tagLine());
            current.setPuuid(found.puuid());
            current.setPuuidKeyId(found.keyId());
            return true;
        });
        cache.evictUser(userId, SOURCES);
        return accounts.findById(userId);
    }

    /** Forgets the member's Riot ID, and what the Riot sources cached for them. */
    public void delete(UserAccount user) {
        boolean deleted = write(user.getId(), false, row -> {
            row.setPuuid(null);
            return true;
        });
        cache.evictUser(user.getId(), SOURCES);
        if (deleted) {
            publish(user.getId());
        }
    }

    /** The settings card's data; built from the row (read once) only, never calling Riot. */
    public RiotData data(UserAccount user) {
        return data(user.getId());
    }

    private RiotData data(UUID userId) {
        Optional<RiotAccount> row = accounts.findById(userId);
        return new RiotData(row.map(RiotAccount::riotId).orElse(null), row.map(RiotAccount::getPlatform).orElse(null),
                properties.riotEnabled() && keys.hasKeys());
    }

    private void publish(UUID userId) {
        try {
            events.riotAccountChanged(userId, data(userId));
        } catch (RuntimeException e) {
            // The settings are saved: a page that is not told refreshes on its next load.
            log.warn("Riot settings of {} saved but not pushed: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Applies {@code change} to the member's row as it is now ({@code create}: a missing row is started), then saves
     * it, or deletes it once it holds no PUUID. A write that crosses another one is redone once on the fresh row,
     * then is a 409.
     *
     * @return false when nothing was written (no row and {@code create} false)
     */
    private boolean write(UUID userId, boolean create, Predicate<RiotAccount> change) {
        try {
            return writeOnce(userId, create, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, create, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("Riot settings of {} crossed another write twice: {}", userId,
                        again.getClass().getSimpleName());
                throw new ApiProblem(ErrorCode.CONFLICT, "The Riot settings changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, boolean create, Predicate<RiotAccount> change) {
        Optional<RiotAccount> found = accounts.findById(userId);
        if (found.isEmpty() && !create) {
            return false;
        }
        RiotAccount row = found.orElseGet(() -> new RiotAccount(userId));
        if (!change.test(row)) {
            return false;
        }
        if (row.getPuuid() == null) {
            accounts.delete(row);
        } else {
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
        }
        return true;
    }

    private static ApiProblem unreachable() {
        return new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "Riot cannot be asked right now");
    }
}
