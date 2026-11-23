package fr.enimaloc.catapult.service.faceit;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.FaceitData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.faceit.FaceitAccount;
import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.repository.faceit.FaceitAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitClient;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Player;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitNotFoundException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A member's FACEIT settings: the nickname they declare. It is checked with FACEIT (which gives the player id later
 * calls use) before anything is written: an unknown nickname is a 400; FACEIT out of reach, the source off or the key
 * pool empty a 503. Each check takes one of the member's refreshes ({@link MemberFetchBudgets}, shared with the stats
 * detail routes): spent, the save is a 429 and FACEIT is not asked. As for RetroAchievements, nothing stops a member from declaring someone else's nickname: the
 * stats are only ever served to the member themselves. Each write re-reads the row and is optimistically locked: one
 * that crosses another is redone once on the fresh row, then answers 409. A changed or removed nickname forgets what
 * the FACEIT sources cached for the member. Telling the owner's pages comes after the write and never fails it.
 */
@Slf4j
@Service
public class FaceitAccountService {

    /** FACEIT nicknames: 2 to 32 letters, digits, dots, dashes or underscores (wider than FACEIT's own rule). */
    static final Pattern NICKNAME = Pattern.compile("[\\p{L}\\p{N}._-]{2,32}");
    static final String PROFILE = "https://www.faceit.com/en/players/";
    static final List<EnrichmentSource> SOURCES = Arrays.stream(EnrichmentSource.values())
            .filter(s -> s.personal() && s.key().startsWith("faceit-")).toList();

    private final FaceitAccountRepository accounts;
    private final FaceitClient client;
    private final FaceitApiKeyRotator keys;
    private final EnrichmentProperties properties;
    private final EnrichmentCache cache;
    private final ChannelEventPublisher events;
    private final MemberFetchBudgets budgets;
    private final Clock clock;

    @Autowired
    public FaceitAccountService(FaceitAccountRepository accounts, FaceitClient client, FaceitApiKeyRotator keys,
                                EnrichmentProperties properties, EnrichmentCache cache,
                                ChannelEventPublisher events, MemberFetchBudgets budgets) {
        this(accounts, client, keys, properties, cache, events, budgets, Clock.systemUTC());
    }

    FaceitAccountService(FaceitAccountRepository accounts, FaceitClient client, FaceitApiKeyRotator keys,
                         EnrichmentProperties properties, EnrichmentCache cache, ChannelEventPublisher events,
                         MemberFetchBudgets budgets, Clock clock) {
        this.accounts = accounts;
        this.client = client;
        this.keys = keys;
        this.properties = properties;
        this.cache = cache;
        this.events = events;
        this.budgets = budgets;
        this.clock = clock;
    }

    /** Declares {@code nickname} (trimmed) as the member's FACEIT nickname, once FACEIT knows it. */
    public void save(UserAccount user, String nickname) {
        String name = nickname == null ? "" : nickname.strip();
        if (!NICKNAME.matcher(name).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid FACEIT nickname");
        }
        if (!properties.faceitEnabled() || !keys.hasKeys()) {
            throw unreachable();
        }
        FetchBudget budget = budgets.forMember(user.getId());
        if (!budget.tryAcquire()) {
            throw new ApiProblem(ErrorCode.TOO_MANY_REQUESTS, "Too many FACEIT checks for your account, try again later",
                    Map.of("retryAfter", Math.max(1, budget.retryAfter().toSeconds())));
        }
        Player found;
        try {
            found = client.playerByNickname(name);
        } catch (FaceitNotFoundException e) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown FACEIT nickname");
        } catch (RuntimeException e) {
            log.warn("FACEIT nickname check failed: {}", e.getClass().getSimpleName());
            throw unreachable();
        }
        boolean[] changed = {false};
        write(user.getId(), true, row -> {
            changed[0] = !found.playerId().equals(row.getPlayerId());
            row.setNickname(found.nickname());
            row.setPlayerId(found.playerId());
            return true;
        });
        if (changed[0]) {
            cache.evictUser(user.getId(), SOURCES);
        }
        publish(user.getId());
    }

    /** Forgets the member's FACEIT nickname, and what the FACEIT sources cached for them. */
    public void delete(UserAccount user) {
        boolean deleted = write(user.getId(), false, row -> {
            row.setPlayerId(null);
            return true;
        });
        cache.evictUser(user.getId(), SOURCES);
        if (deleted) {
            publish(user.getId());
        }
    }

    /** The settings card's data; built from the row (read once) only, never calling FACEIT. */
    public FaceitData data(UserAccount user) {
        return data(user.getId());
    }

    private FaceitData data(UUID userId) {
        Optional<FaceitAccount> row = accounts.findById(userId);
        String nickname = row.map(FaceitAccount::getNickname).orElse(null);
        return new FaceitData(nickname,
                nickname == null ? null : PROFILE + UriUtils.encodePathSegment(nickname, StandardCharsets.UTF_8),
                properties.faceitEnabled() && keys.hasKeys());
    }

    private void publish(UUID userId) {
        try {
            events.faceitAccountChanged(userId, data(userId));
        } catch (RuntimeException e) {
            // The settings are saved: a page that is not told refreshes on its next load.
            log.warn("FACEIT settings of {} saved but not pushed: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Applies {@code change} to the member's row as it is now ({@code create}: a missing row is started), then saves
     * it, or deletes it once it holds no player id. A write that crosses another one is redone once on the fresh row,
     * then is a 409.
     *
     * @return false when nothing was written (no row and {@code create} false)
     */
    private boolean write(UUID userId, boolean create, Predicate<FaceitAccount> change) {
        try {
            return writeOnce(userId, create, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, create, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("FACEIT settings of {} crossed another write twice: {}", userId,
                        again.getClass().getSimpleName());
                throw new ApiProblem(ErrorCode.CONFLICT, "The FACEIT settings changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, boolean create, Predicate<FaceitAccount> change) {
        Optional<FaceitAccount> found = accounts.findById(userId);
        if (found.isEmpty() && !create) {
            return false;
        }
        FaceitAccount row = found.orElseGet(() -> new FaceitAccount(userId));
        if (!change.test(row)) {
            return false;
        }
        if (row.getPlayerId() == null) {
            accounts.delete(row);
        } else {
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
        }
        return true;
    }

    private static ApiProblem unreachable() {
        return new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "FACEIT cannot be asked right now");
    }
}
