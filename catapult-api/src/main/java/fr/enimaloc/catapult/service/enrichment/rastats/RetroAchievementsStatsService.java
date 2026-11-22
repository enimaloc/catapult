package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.GameNames;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.AchievementSummary;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.retroachievements.RetroAchievementsAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's RetroAchievements stats, through the shared cache: the profile and the games played lately
 * ({@code ra-user} / {@code u:<uuid>}), the progress in one game ({@code ra-progress} /
 * {@code u:<uuid>:game:<gameId>}) and the achievements of the last 24 hours ({@code ra-recent} / {@code u:<uuid>}).
 * Every call is made with the member's personal key while RetroAchievements accepts it, else the pool's: a personal
 * key refused is marked so and the call is made once more with the pool. Without a declared account or a key to ask
 * with, there are no stats; an outage is never cached and says {@code UNAVAILABLE}. An untracked account says
 * {@code PRIVATE}.
 */
@Slf4j
@Service
public class RetroAchievementsStatsService {

    /** How many games played lately are read to find the one played now. */
    static final int RECENT_GAMES = 10;
    /** The window of the recent achievements of the account, in minutes. */
    static final int RECENT_MINUTES = 1440;
    /** A manual game is taken for the last one played on RetroAchievements if it was played this recently. */
    static final long PLAYING_NOW_MINUTES = 15;
    static final Duration PLAYING_NOW = Duration.ofMinutes(PLAYING_NOW_MINUTES);
    private static final String PROFILE = "https://retroachievements.org/user/";

    /** The keys of {@link PlatformStats#extra()} for RetroAchievements. */
    public static final String TITLE = "title";
    public static final String CONSOLE = "console";
    public static final String ICON_URL = "iconUrl";
    /** {@code UserTotalPlaytime} as RetroAchievements gives it (its unit is not verified, so it is not minutes). */
    public static final String PLAYTIME = "playtime";
    public static final String POINTS = "points";
    public static final String POINTS_TOTAL = "pointsTotal";
    public static final String HARDCORE_UNLOCKED = "hardcoreUnlocked";
    public static final String HARDCORE_POINTS = "hardcorePoints";
    public static final String AWARD = "award";

    /** The cached shapes (the cache stores one object per key). */
    public record Player(RaUser profile, List<RaRecentGame> recentlyPlayed) {
    }

    public record Unlocks(List<RaAchievement> achievements) {
    }

    /** A cached value, or what kept it from being read: an outage, or no key to ask with. */
    private record Looked<T>(Optional<T> value, boolean failed, boolean noKey) {
    }

    /** No personal key usable and no pool key: RetroAchievements cannot be asked, which is not an outage. */
    private static final class NoKeyException extends RuntimeException {
        NoKeyException() {
            super("No RetroAchievements key to ask with", null, false, false);
        }
    }

    /** The member being read, and whether their personal key was refused during this read. */
    private static final class Member {
        private final UserAccount user;
        private final RetroAchievementsAccount row;
        private volatile boolean personalRefused;

        Member(UserAccount user, RetroAchievementsAccount row) {
            this.user = user;
            this.row = row;
        }

        String key() {
            return "u:" + user.getId();
        }
    }

    private final EnrichmentCache cache;
    private final RetroAchievementsClient client;
    private final RetroAchievementsAccountRepository accounts;
    private final RetroAchievementsAccountService accountService;
    private final EnrichmentProperties properties;
    private final Clock clock;

    @Autowired
    public RetroAchievementsStatsService(EnrichmentCache cache, RetroAchievementsClient client,
                                         RetroAchievementsAccountRepository accounts,
                                         RetroAchievementsAccountService accountService,
                                         EnrichmentProperties properties) {
        this(cache, client, accounts, accountService, properties, Clock.systemUTC());
    }

    RetroAchievementsStatsService(EnrichmentCache cache, RetroAchievementsClient client,
                                  RetroAchievementsAccountRepository accounts,
                                  RetroAchievementsAccountService accountService, EnrichmentProperties properties,
                                  Clock clock) {
        this.cache = cache;
        this.client = client;
        this.accounts = accounts;
        this.accountService = accountService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The RetroAchievements game the member plays now, among their games played lately: the one whose normalized
     * title is {@code searchName}'s (hacks, {@code ~…}, and subsets, {@code [Subset…}, left out); else, for a
     * manual game, the last one played if it was less than {@value #PLAYING_NOW_MINUTES} minutes ago. Empty when
     * none or several match, the member has no account or no key, or RetroAchievements is off.
     *
     * @throws RaUnavailableException when the games played lately cannot be read and nothing stale is cached
     */
    public Optional<Integer> resolveGame(UserAccount user, DetectedGame detected, String searchName,
                                         FetchBudget budget) {
        Optional<Member> member = member(user, EnrichmentSource.RA_USER, EnrichmentSource.RA_PROGRESS);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        Looked<Player> player = player(member.get(), budget);
        if (player.value().isEmpty()) {
            if (player.failed() && !player.noKey()) {
                throw new RaUnavailableException("RetroAchievements games played lately unavailable");
            }
            return Optional.empty();
        }
        List<RaRecentGame> games = player.value().get().recentlyPlayed();
        if (games == null || games.isEmpty()) {
            return Optional.empty();
        }
        String wanted = searchName == null ? "" : GameNames.normalize(searchName);
        if (!wanted.isEmpty()) {
            Set<Integer> byName = new LinkedHashSet<>();
            for (RaRecentGame game : games) {
                if (game.title() != null && !hackOrSubset(game.title())
                        && GameNames.normalize(game.title()).equals(wanted)) {
                    byName.add(game.gameId());
                }
            }
            if (!byName.isEmpty()) {
                return byName.size() == 1 ? Optional.of(byName.iterator().next()) : Optional.empty();
            }
        }
        RaRecentGame last = games.getFirst();
        if (detected != null && detected.getSourceType() == GameBinding.SourceType.MANUAL
                && last.lastPlayed() != null && last.lastPlayed().plus(PLAYING_NOW).isAfter(clock.instant())) {
            return Optional.of(last.gameId());
        }
        return Optional.empty();
    }

    /**
     * The member's stats on RetroAchievements game {@code gameId}; empty when the member has no account or no key,
     * RetroAchievements does not know the game or the account, or a source is off. {@code detail} also lists every
     * achievement. A rarity is the share of the game's players who unlocked the achievement.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<PlatformStats> forGame(UserAccount user, int gameId, FetchBudget budget, boolean detail) {
        Optional<Member> found = member(user, EnrichmentSource.RA_USER, EnrichmentSource.RA_PROGRESS);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Member member = found.get();
        String id = String.valueOf(gameId);
        Looked<Player> player = player(member, budget);
        if (player.value().isEmpty()) {
            return absentOrUnavailable(player, id);
        }
        if (player.value().get().profile().untracked()) {
            return Optional.of(PlatformStats.of(Status.PRIVATE, id));
        }
        Looked<RaGameProgress> progress = look(EnrichmentSource.RA_PROGRESS, member.key() + ":game:" + gameId,
                RaGameProgress.class, budget,
                () -> withKey(member, key -> client.progress(gameId, member.row.getUserUlid(), key)));
        if (progress.value().isEmpty()) {
            return absentOrUnavailable(progress, id);
        }
        RaGameProgress game = progress.value().get();
        Instant lastPlayed = player.value().get().recentlyPlayed() == null ? null
                : player.value().get().recentlyPlayed().stream().filter(g -> g.gameId() == gameId)
                        .map(RaRecentGame::lastPlayed).findFirst().orElse(null);
        List<Achievement> all = game.achievements() == null ? List.of()
                : game.achievements().stream().map(a -> achievement(a, game.numDistinctPlayers())).toList();
        Map<String, Object> extra = extra(game);
        if (all.isEmpty()) {
            return Optional.of(new PlatformStats(Status.NO_ACHIEVEMENTS, id, null, null, lastPlayed, null, null,
                    extra));
        }
        return Optional.of(new PlatformStats(Status.OK, id, null, null, lastPlayed, AchievementSummary.of(all),
                detail ? all : null, extra));
    }

    /**
     * The member's account: profile, games played lately and achievements of the last 24 hours; empty when the
     * member has no account or no key, RetroAchievements does not know the account, or it is off.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<RaAccountView> account(UserAccount user, FetchBudget budget) {
        Optional<Member> found = member(user, EnrichmentSource.RA_USER);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Member member = found.get();
        String username = member.row.getUsername();
        Looked<Player> player = player(member, budget);
        if (player.value().isEmpty()) {
            if (player.failed() && !player.noKey()) {
                return Optional.of(new RaAccountView(Status.UNAVAILABLE, username, PROFILE + username, null, null,
                        null, null, null, null, null, null));
            }
            return Optional.empty();
        }
        RaUser profile = player.value().get().profile();
        String url = PROFILE + profile.user();
        if (profile.untracked()) {
            return Optional.of(new RaAccountView(Status.PRIVATE, profile.user(), url, profile.picUrl(), null, null,
                    null, null, null, null, null));
        }
        List<RaAchievement> recent = null;
        if (properties.source(EnrichmentSource.RA_RECENT).enabled()) {
            recent = look(EnrichmentSource.RA_RECENT, member.key(), Unlocks.class, budget,
                    () -> Optional.of(new Unlocks(withKey(member, key ->
                            client.recentAchievements(member.row.getUserUlid(), RECENT_MINUTES, key)))))
                    .value().map(Unlocks::achievements).orElse(null);
        }
        return Optional.of(new RaAccountView(Status.OK, profile.user(), url, profile.picUrl(),
                profile.totalPoints(), profile.totalSoftcorePoints(), profile.totalTruePoints(),
                profile.richPresence(), profile.lastGameId(), player.value().get().recentlyPlayed(), recent));
    }

    /**
     * The member and their account, when every source in {@code needed} is on, the account has a name and there is
     * a key to ask with (checked before the cache is read: no key is "nothing known", not an outage to log).
     */
    private Optional<Member> member(UserAccount user, EnrichmentSource... needed) {
        for (EnrichmentSource source : needed) {
            if (!properties.source(source).enabled()) {
                // a source off is "nothing known", never a status
                return Optional.empty();
            }
        }
        return accounts.findById(user.getId())
                .filter(row -> row.getUsername() != null && row.getUserUlid() != null)
                .filter(row -> accountService.credentials(user, Optional.of(row)).isPresent())
                .map(row -> new Member(user, row));
    }

    /** The profile and the games played lately; empty (not failed) when RetroAchievements does not know the account. */
    private Looked<Player> player(Member member, FetchBudget budget) {
        String ulid = member.row.getUserUlid();
        return look(EnrichmentSource.RA_USER, member.key(), Player.class, budget, () -> {
            Optional<RaUser> profile = withKey(member, key -> client.profile(ulid, key));
            if (profile.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Player(profile.get(),
                    withKey(member, key -> client.recentlyPlayed(ulid, RECENT_GAMES, key))));
        });
    }

    private static Optional<PlatformStats> absentOrUnavailable(Looked<?> looked, String id) {
        if (looked.failed() && !looked.noKey()) {
            return Optional.of(PlatformStats.of(Status.UNAVAILABLE, id));
        }
        return Optional.empty();
    }

    /**
     * One call with the member's personal key, else the pool's. A personal key refused is marked invalid and the call
     * is made once more with the pool; a pool key refused (the rotator sets it aside) is an outage.
     */
    private <T> T withKey(Member member, Function<RaCredentials, T> call) {
        RaCredentials credentials = credentials(member);
        try {
            return call.apply(credentials);
        } catch (RaKeyRejectedException e) {
            if (!credentials.personal()) {
                throw new RaUnavailableException("RetroAchievements refused a pool key");
            }
            member.personalRefused = true;
            markKeyInvalid(member);
            RaCredentials pool = credentials(member);
            try {
                return call.apply(pool);
            } catch (RaKeyRejectedException again) {
                throw new RaUnavailableException("RetroAchievements refused a pool key");
            }
        }
    }

    private RaCredentials credentials(Member member) {
        Optional<RetroAchievementsAccount> row = member.personalRefused ? Optional.empty() : Optional.of(member.row);
        return accountService.credentials(member.user, row).orElseThrow(NoKeyException::new);
    }

    private void markKeyInvalid(Member member) {
        try {
            accountService.markKeyInvalid(member.user.getId());
        } catch (RuntimeException e) {
            // the pool still answers this read; the key is marked at its next refusal
            log.warn("RetroAchievements key of {} refused but not marked: {}", member.user.getId(),
                    e.getClass().getSimpleName());
        }
    }

    private static boolean hackOrSubset(String title) {
        return title.strip().startsWith("~") || title.contains("[Subset");
    }

    private static Achievement achievement(RaAchievement a, int players) {
        boolean unlocked = a.dateEarned() != null;
        return new Achievement(String.valueOf(a.id()), a.title(), a.description(), a.badgeUrl(), unlocked,
                a.dateEarned(), rarity(a.numAwarded(), players), a.points(), false);
    }

    /** {@code awarded / players × 100}, two decimals; null without players or without a count. */
    static BigDecimal rarity(Integer awarded, int players) {
        if (awarded == null || players <= 0) {
            return null;
        }
        return BigDecimal.valueOf(awarded).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(players), 2, RoundingMode.HALF_UP);
    }

    /** The game and what only RetroAchievements has: points, hardcore progress, award, raw play time. */
    private static Map<String, Object> extra(RaGameProgress game) {
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, TITLE, game.title());
        putIfPresent(extra, CONSOLE, game.consoleName());
        putIfPresent(extra, ICON_URL, game.iconUrl());
        putIfPresent(extra, PLAYTIME, game.userTotalPlaytime());
        List<RaAchievement> achievements = game.achievements() == null ? List.of() : game.achievements();
        extra.put(POINTS, achievements.stream().filter(a -> a.dateEarned() != null)
                .mapToInt(RaAchievement::points).sum());
        extra.put(POINTS_TOTAL, achievements.stream().mapToInt(RaAchievement::points).sum());
        extra.put(HARDCORE_UNLOCKED, game.numAwardedToUserHardcore());
        extra.put(HARDCORE_POINTS, achievements.stream().filter(a -> a.dateEarnedHardcore() != null)
                .mapToInt(RaAchievement::points).sum());
        putIfPresent(extra, AWARD, game.highestAwardKind());
        return extra;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * The cached or fetched value; when the fetch fails and nothing stale can be served, {@code failed} says it
     * failed and {@code noKey} whether it was for want of a key. Any failure is recorded: the cache swallows them,
     * and a parsing error must not read as "absent".
     */
    private <T> Looked<T> look(EnrichmentSource source, String key, Class<T> type, FetchBudget budget,
                               Supplier<Optional<T>> fetch) {
        boolean[] why = new boolean[2];
        Optional<T> value = cache.get(source, key, type, budget, () -> {
            try {
                return fetch.get();
            } catch (NoKeyException e) {
                why[0] = true;
                why[1] = true;
                throw e;
            } catch (RuntimeException e) {
                why[0] = true;
                throw e;
            }
        });
        return new Looked<>(value, why[0], why[1]);
    }
}
