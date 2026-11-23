package fr.enimaloc.catapult.service.supercell;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.SupercellData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.supercell.SupercellAccount;
import fr.enimaloc.catapult.repository.supercell.SupercellAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellClient;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellNotFoundException;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStatsService;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellTag;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A member's Supercell settings: the player tag they declare in each game (Clash of Clans, Clash Royale, Brawl
 * Stars). The tag is normalized ({@code #} added, upper case, O read as 0) and checked with that game's API before
 * anything is written: a malformed tag or an unknown game is a 400 that costs nothing, a tag no player has a 400;
 * Supercell out of reach, the source off or no key of the game in the pool a 503. The check calls Supercell, so it
 * takes one of the member's refreshes first (a 429 when they are spent). As for RetroAchievements, nothing stops a
 * member from declaring someone else's tag: the stats are only ever served to the member themselves. Each write
 * re-reads the row and is optimistically locked: one that crosses another is redone once on the fresh row, then
 * answers 409. A changed or removed tag forgets what that game's source cached for the member. Telling the owner's
 * pages comes after the write and never fails it.
 */
@Slf4j
@Service
public class SupercellAccountService {

    private final SupercellAccountRepository accounts;
    private final SupercellClient client;
    private final SupercellStatsService stats;
    private final EnrichmentCache cache;
    private final MemberFetchBudgets budgets;
    private final ChannelEventPublisher events;
    private final Clock clock;

    @Autowired
    public SupercellAccountService(SupercellAccountRepository accounts, SupercellClient client,
                                   SupercellStatsService stats, EnrichmentCache cache, MemberFetchBudgets budgets,
                                   ChannelEventPublisher events) {
        this(accounts, client, stats, cache, budgets, events, Clock.systemUTC());
    }

    SupercellAccountService(SupercellAccountRepository accounts, SupercellClient client, SupercellStatsService stats,
                            EnrichmentCache cache, MemberFetchBudgets budgets, ChannelEventPublisher events,
                            Clock clock) {
        this.accounts = accounts;
        this.client = client;
        this.stats = stats;
        this.cache = cache;
        this.budgets = budgets;
        this.events = events;
        this.clock = clock;
    }

    private static SupercellGame game(String game) {
        return SupercellGame.parse(game).orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST,
                "Unknown Supercell game (clash-of-clans, clash-royale or brawl-stars)"));
    }

    /** Declares {@code rawTag} as the member's player in {@code gameKey}, once that game's API knows it. */
    public void save(UserAccount user, String gameKey, String rawTag) {
        SupercellGame game = game(gameKey);
        String tag = SupercellTag.normalize(rawTag)
                .orElseThrow(() -> new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid player tag"));
        if (!stats.available(game)) {
            throw unreachable();
        }
        FetchBudget budget = budgets.forMember(user.getId());
        if (!budget.tryAcquire()) {
            throw new ApiProblem(ErrorCode.TOO_MANY_REQUESTS, "Too many checks for your account, try again later",
                    Map.of("retryAfter", Math.max(1, budget.retryAfter().toSeconds())));
        }
        SupercellPlayer found;
        try {
            found = client.player(game, tag);
        } catch (SupercellNotFoundException e) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "No " + game.title() + " player has this tag");
        } catch (RuntimeException e) {
            log.warn("Supercell player check failed: {}", e.getClass().getSimpleName());
            throw unreachable();
        }
        boolean[] changed = {false};
        write(user.getId(), true, row -> {
            changed[0] = !tag.equals(row.tag(game));
            row.set(game, tag, found.name());
            return true;
        });
        if (changed[0]) {
            cache.evictUser(user.getId(), List.of(SupercellStatsService.source(game)));
        }
        // the player just checked serves the first read too (after the eviction, which would remove it)
        cache.put(SupercellStatsService.source(game), SupercellStatsService.cacheKey(user.getId(), tag),
                new SupercellStatsService.PlayerCell(found));
        publish(user.getId());
    }

    /** Forgets the member's player tag in {@code gameKey}, and what that game's source cached for them. */
    public void delete(UserAccount user, String gameKey) {
        SupercellGame game = game(gameKey);
        boolean deleted = write(user.getId(), false, row -> {
            row.set(game, null, null);
            return true;
        });
        cache.evictUser(user.getId(), List.of(SupercellStatsService.source(game)));
        if (deleted) {
            publish(user.getId());
        }
    }

    /** The settings card's data; built from the row (read once) only, never calling Supercell. */
    public SupercellData data(UserAccount user) {
        return data(user.getId());
    }

    private SupercellData data(UUID userId) {
        Optional<SupercellAccount> row = accounts.findById(userId);
        return new SupercellData(game(row, SupercellGame.CLASH_OF_CLANS), game(row, SupercellGame.CLASH_ROYALE),
                game(row, SupercellGame.BRAWL_STARS));
    }

    private SupercellData.Game game(Optional<SupercellAccount> row, SupercellGame game) {
        return new SupercellData.Game(row.map(r -> r.tag(game)).orElse(null),
                row.map(r -> r.name(game)).orElse(null), stats.available(game));
    }

    private void publish(UUID userId) {
        try {
            events.supercellAccountChanged(userId, data(userId));
        } catch (RuntimeException e) {
            // The settings are saved: a page that is not told refreshes on its next load.
            log.warn("Supercell settings of {} saved but not pushed: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Applies {@code change} to the member's row as it is now ({@code create}: a missing row is started), then saves
     * it, or deletes it once it holds no tag. A write that crosses another one is redone once on the fresh row, then
     * is a 409.
     *
     * @return false when nothing was written (no row and {@code create} false)
     */
    private boolean write(UUID userId, boolean create, Predicate<SupercellAccount> change) {
        try {
            return writeOnce(userId, create, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, create, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("Supercell settings of {} crossed another write twice: {}", userId,
                        again.getClass().getSimpleName());
                throw new ApiProblem(ErrorCode.CONFLICT, "The Supercell settings changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, boolean create, Predicate<SupercellAccount> change) {
        Optional<SupercellAccount> found = accounts.findById(userId);
        if (found.isEmpty() && !create) {
            return false;
        }
        SupercellAccount row = found.orElseGet(() -> new SupercellAccount(userId));
        if (!change.test(row)) {
            return false;
        }
        if (row.empty()) {
            if (found.isPresent()) {
                accounts.delete(row);
            }
        } else {
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
        }
        return true;
    }

    private static ApiProblem unreachable() {
        return new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "Supercell cannot be asked right now");
    }
}
