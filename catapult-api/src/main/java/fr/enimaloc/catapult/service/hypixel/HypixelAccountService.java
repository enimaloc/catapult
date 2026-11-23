package fr.enimaloc.catapult.service.hypixel;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.HypixelData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.hypixel.HypixelAccount;
import fr.enimaloc.catapult.getter.hypixel.HypixelApiKeyRotator;
import fr.enimaloc.catapult.repository.hypixel.HypixelAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelClient;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.MinecraftProfile;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelNotFoundException;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStatsService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A member's Hypixel settings: the Minecraft account they declare, by name (looked up at Mojang) or by UUID. The
 * account is checked with Hypixel (it must have joined the server) before anything is written: an unknown name or a
 * UUID that never joined is a 400; Hypixel or Mojang out of reach, or the source off, a 503. The check calls the
 * upstreams, so it takes one of the member's refreshes first (a 429 when they are spent; a malformed entry costs
 * nothing). As for RetroAchievements, nothing stops a member from declaring someone else's account: the stats are
 * only ever served to the member themselves. Each write re-reads the row and is optimistically locked: one that
 * crosses another is redone once on the fresh row, then answers 409. A changed or removed account forgets what the
 * Hypixel sources cached for the member. Telling the owner's pages comes after the write and never fails it.
 */
@Slf4j
@Service
public class HypixelAccountService {

    /** A Minecraft name: 3 to 16 letters, digits or underscores. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** A Minecraft UUID, with or without its dashes. */
    static final Pattern UUID_FORM = Pattern.compile(
            "[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final List<EnrichmentSource> SOURCES = List.of(EnrichmentSource.HYPIXEL_PLAYER,
            EnrichmentSource.HYPIXEL_STATUS);

    private final HypixelAccountRepository accounts;
    private final HypixelClient client;
    private final HypixelApiKeyRotator keys;
    private final EnrichmentProperties properties;
    private final EnrichmentCache cache;
    private final MemberFetchBudgets budgets;
    private final ChannelEventPublisher events;
    private final Clock clock;

    @Autowired
    public HypixelAccountService(HypixelAccountRepository accounts, HypixelClient client, HypixelApiKeyRotator keys,
                                 EnrichmentProperties properties, EnrichmentCache cache, MemberFetchBudgets budgets,
                                 ChannelEventPublisher events) {
        this(accounts, client, keys, properties, cache, budgets, events, Clock.systemUTC());
    }

    HypixelAccountService(HypixelAccountRepository accounts, HypixelClient client, HypixelApiKeyRotator keys,
                          EnrichmentProperties properties, EnrichmentCache cache, MemberFetchBudgets budgets,
                          ChannelEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.client = client;
        this.keys = keys;
        this.properties = properties;
        this.cache = cache;
        this.budgets = budgets;
        this.events = events;
        this.clock = clock;
    }

    /** Declares {@code player} (a Minecraft name or UUID, trimmed), once Hypixel knows it. */
    public void save(UserAccount user, String player) {
        String raw = player == null ? "" : player.strip();
        boolean byUuid = UUID_FORM.matcher(raw).matches();
        if (!byUuid && !NAME.matcher(raw).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid Minecraft name or UUID");
        }
        if (!properties.hypixelEnabled() || !keys.hasKeys()) {
            throw unreachable();
        }
        FetchBudget budget = budgets.forMember(user.getId());
        if (!budget.tryAcquire()) {
            throw new ApiProblem(ErrorCode.TOO_MANY_REQUESTS, "Too many checks for your account, try again later",
                    Map.of("retryAfter", Math.max(1, budget.retryAfter().toSeconds())));
        }
        String uuid;
        String name = null;
        try {
            if (byUuid) {
                uuid = raw.replace("-", "").toLowerCase(Locale.ROOT);
            } else {
                MinecraftProfile profile = client.profileByName(raw);
                uuid = profile.uuid();
                name = profile.name();
            }
        } catch (HypixelNotFoundException e) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown Minecraft name");
        } catch (RuntimeException e) {
            log.warn("Minecraft name lookup failed: {}", e.getClass().getSimpleName());
            throw unreachable();
        }
        HypixelPlayer found;
        try {
            found = client.player(uuid);
        } catch (HypixelNotFoundException e) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "This Minecraft account never joined Hypixel");
        } catch (RuntimeException e) {
            log.warn("Hypixel player check failed: {}", e.getClass().getSimpleName());
            throw unreachable();
        }
        String shown = found.displayName() != null ? found.displayName() : name != null ? name : uuid;
        boolean[] changed = {false};
        write(user.getId(), true, row -> {
            changed[0] = !uuid.equals(row.getMinecraftUuid());
            row.setMinecraftUuid(uuid);
            row.setMinecraftName(shown);
            return true;
        });
        if (changed[0]) {
            cache.evictUser(user.getId(), SOURCES);
        }
        // the player just checked serves the first read too (after the eviction, which would remove it)
        cache.put(EnrichmentSource.HYPIXEL_PLAYER, "u:" + user.getId() + ":" + uuid,
                new HypixelStatsService.PlayerCell(found));
        publish(user.getId());
    }

    /** Forgets the member's Minecraft account, and what the Hypixel sources cached for them. */
    public void delete(UserAccount user) {
        boolean deleted = write(user.getId(), false, row -> {
            row.setMinecraftUuid(null);
            return true;
        });
        cache.evictUser(user.getId(), SOURCES);
        if (deleted) {
            publish(user.getId());
        }
    }

    /** The settings card's data; built from the row (read once) only, never calling Hypixel. */
    public HypixelData data(UserAccount user) {
        return data(user.getId());
    }

    private HypixelData data(UUID userId) {
        Optional<HypixelAccount> row = accounts.findById(userId);
        return new HypixelData(row.map(HypixelAccount::getMinecraftName).orElse(null),
                row.map(HypixelAccount::getMinecraftUuid).orElse(null),
                properties.hypixelEnabled() && keys.hasKeys()
                        && properties.source(EnrichmentSource.HYPIXEL_PLAYER).enabled());
    }

    private void publish(UUID userId) {
        try {
            events.hypixelAccountChanged(userId, data(userId));
        } catch (RuntimeException e) {
            // The settings are saved: a page that is not told refreshes on its next load.
            log.warn("Hypixel settings of {} saved but not pushed: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Applies {@code change} to the member's row as it is now ({@code create}: a missing row is started), then saves
     * it, or deletes it once it holds no UUID. A write that crosses another one is redone once on the fresh row,
     * then is a 409.
     *
     * @return false when nothing was written (no row and {@code create} false)
     */
    private boolean write(UUID userId, boolean create, Predicate<HypixelAccount> change) {
        try {
            return writeOnce(userId, create, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, create, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("Hypixel settings of {} crossed another write twice: {}", userId,
                        again.getClass().getSimpleName());
                throw new ApiProblem(ErrorCode.CONFLICT, "The Hypixel settings changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, boolean create, Predicate<HypixelAccount> change) {
        Optional<HypixelAccount> found = accounts.findById(userId);
        if (found.isEmpty() && !create) {
            return false;
        }
        HypixelAccount row = found.orElseGet(() -> new HypixelAccount(userId));
        if (!change.test(row)) {
            return false;
        }
        if (row.getMinecraftUuid() == null) {
            accounts.delete(row);
        } else {
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
        }
        return true;
    }

    private static ApiProblem unreachable() {
        return new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "Hypixel cannot be asked right now");
    }
}
