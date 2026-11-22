package fr.enimaloc.catapult.service.enrichment.xboxstats;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.GameNames;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.AchievementSummary;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService.XstsSession;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A member's Xbox play time and achievements on one title, through the shared cache: the title history per member
 * ({@code xbox-titles} / {@code u:<uuid>}), the achievements ({@code xbox-achievements}) and the play time
 * ({@code xbox-playtime}) per member and title ({@code u:<uuid>:title:<titleId>}). An outage is never cached: the
 * stats say {@code UNAVAILABLE}, and the next call asks again. When Xbox refuses the achievements (the rights of
 * the session do not cover them), the stats say {@code UNSUPPORTED} with the Titlehub summary, and the refusal is
 * not tried again for an hour.
 */
@Slf4j
@Service
public class XboxStatsService {

    static final int MAX_TITLES = 1000;
    static final Duration REFUSAL_BACKOFF = Duration.ofHours(1);

    /** The cached shapes (the cache stores one object per key). */
    public record Titles(List<XboxTitle> titles) {
    }

    public record Unlocks(List<Achievement> achievements) {
    }

    public record Minutes(long minutes) {
    }

    /** A cached value, or what kept it from being read: an outage, or Xbox refusing the session. */
    private record Looked<T>(Optional<T> value, boolean failed, boolean forbidden) {
    }

    private final Optional<XboxUserTokenService> tokens;
    private final OAuthTokenRepository oauthTokens;
    private final XboxStatsClient client;
    private final EnrichmentCache cache;
    private final EnrichmentProperties properties;
    private final Clock clock;
    /** When Xbox last refused a member's achievements / play time reads. */
    private final Map<UUID, Instant> achievementsRefused = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> playtimeRefused = new ConcurrentHashMap<>();

    @Autowired
    public XboxStatsService(Optional<XboxUserTokenService> tokens, OAuthTokenRepository oauthTokens,
                            XboxStatsClient client, EnrichmentCache cache, EnrichmentProperties properties) {
        this(tokens, oauthTokens, client, cache, properties, Clock.systemUTC());
    }

    XboxStatsService(Optional<XboxUserTokenService> tokens, OAuthTokenRepository oauthTokens, XboxStatsClient client,
                     EnrichmentCache cache, EnrichmentProperties properties, Clock clock) {
        this.properties = properties;
        this.tokens = tokens;
        this.oauthTokens = oauthTokens;
        this.client = client;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * The titleId of the game in the member's Xbox library: the detected Xbox game's numeric id, else the history
     * entry carrying the detected / IGDB Store Product ID, else the one entry whose normalized name is
     * {@code searchName}'s. Empty when none or several match, Xbox is off, or the member has no Xbox link.
     *
     * @throws XboxStatsUnavailableException when the history is needed but cannot be read (outage, no session) and
     *         nothing stale is cached: "unavailable", as opposed to "not found"
     */
    public Optional<String> resolveTitle(UserAccount user, DetectedGame detected, Optional<String> storeIdFromIgdb,
                                         String searchName, FetchBudget budget) {
        if (tokens.isEmpty() || !enabled()) {
            return Optional.empty();
        }
        boolean xbox = detected != null && detected.getSourceType() == GameBinding.SourceType.XBOX
                && detected.getSourceId() != null;
        if (xbox && detected.getSourceId().matches("\\d+")) {
            return Optional.of(detected.getSourceId());
        }
        if (!linked(user)) {
            return Optional.empty();
        }
        Looked<Titles> history = history(user, budget);
        if (history.value().isEmpty()) {
            if (history.failed()) {
                throw new XboxStatsUnavailableException("Xbox title history unavailable");
            }
            return Optional.empty();
        }
        List<XboxTitle> titles = history.value().get().titles();

        Set<String> productIds = new LinkedHashSet<>();
        if (xbox) {
            productIds.add(detected.getSourceId().toLowerCase());
        }
        storeIdFromIgdb.filter(id -> !id.isBlank()).ifPresent(id -> productIds.add(id.toLowerCase()));
        Set<String> byProduct = new LinkedHashSet<>();
        for (XboxTitle title : titles) {
            if (title.productIds() != null
                    && title.productIds().stream().anyMatch(id -> productIds.contains(id.toLowerCase()))) {
                byProduct.add(title.titleId());
            }
        }
        if (!byProduct.isEmpty()) {
            return byProduct.size() == 1 ? Optional.of(byProduct.iterator().next()) : Optional.empty();
        }
        if (searchName == null || GameNames.normalize(searchName).isEmpty()) {
            return Optional.empty();
        }
        String wanted = GameNames.normalize(searchName);
        Set<String> byName = new LinkedHashSet<>();
        for (XboxTitle title : titles) {
            if (title.name() != null && GameNames.normalize(title.name()).equals(wanted)) {
                byName.add(title.titleId());
            }
        }
        return byName.size() == 1 ? Optional.of(byName.iterator().next()) : Optional.empty();
    }

    /**
     * The member's stats on Xbox title {@code titleId}; empty when Xbox is off, the member has no Xbox link, or the
     * title is not in their history. {@code detail} also lists every achievement.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<PlatformStats> forTitle(UserAccount user, String titleId, FetchBudget budget, boolean detail) {
        if (tokens.isEmpty() || !enabled() || !linked(user)) {
            return Optional.empty();
        }
        Looked<Titles> history = history(user, budget);
        if (history.value().isEmpty()) {
            return history.failed() ? Optional.of(PlatformStats.of(Status.UNAVAILABLE, titleId)) : Optional.empty();
        }
        Optional<XboxTitle> found = history.value().get().titles().stream()
                .filter(t -> titleId.equals(t.titleId())).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        XboxTitle title = found.get();
        String member = "u:" + user.getId();
        String key = member + ":title:" + titleId;
        Supplier<XstsSession> session = session(user);

        Long minutes = minutes(user, titleId, key, session, budget);

        if (title.totalAchievements() != null && title.totalAchievements() == 0) {
            return Optional.of(stats(Status.NO_ACHIEVEMENTS, title, minutes, null, null, null));
        }
        if (refused(achievementsRefused, user.getId())) {
            return Optional.of(unsupported(title, minutes));
        }
        Looked<Unlocks> unlocks = look(EnrichmentSource.XBOX_ACHIEVEMENTS, key, Unlocks.class, budget,
                () -> Optional.of(new Unlocks(client.achievements(user.getId(), session.get(), titleId))));
        if (unlocks.forbidden()) {
            achievementsRefused.put(user.getId(), clock.instant());
            return Optional.of(unsupported(title, minutes));
        }
        if (unlocks.value().isEmpty()) {
            return Optional.of(PlatformStats.of(Status.UNAVAILABLE, titleId));
        }
        List<Achievement> all = unlocks.value().get().achievements().stream().map(XboxStatsService::masked).toList();
        if (all.isEmpty()) {
            // Titlehub announces achievements the v2 contract does not list: an Xbox 360 title.
            return Optional.of(stats(Status.OK, title, minutes, titleSummary(title), detail ? List.of() : null,
                    gamerscore(title, all)));
        }
        return Optional.of(stats(Status.OK, title, minutes, AchievementSummary.of(all), detail ? all : null,
                gamerscore(title, all)));
    }

    private Long minutes(UserAccount user, String titleId, String key, Supplier<XstsSession> session,
                         FetchBudget budget) {
        if (refused(playtimeRefused, user.getId())) {
            return null;
        }
        Looked<Minutes> played = look(EnrichmentSource.XBOX_PLAYTIME, key, Minutes.class, budget,
                () -> client.minutesPlayed(user.getId(), session.get(), titleId).map(Minutes::new));
        if (played.forbidden()) {
            playtimeRefused.put(user.getId(), clock.instant());
        }
        return played.value().map(Minutes::minutes).orElse(null);
    }

    private PlatformStats unsupported(XboxTitle title, Long minutes) {
        return stats(Status.UNSUPPORTED, title, minutes, titleSummary(title), null, gamerscore(title, List.of()));
    }

    private static PlatformStats stats(Status status, XboxTitle title, Long minutes, AchievementSummary summary,
                                       List<Achievement> all, Map<String, Object> extra) {
        return new PlatformStats(status, title.titleId(), minutes, null, title.lastTimePlayed(), summary, all,
                extra == null ? Map.of() : extra);
    }

    /** The summary Titlehub gives (counters only); null when it gave none. */
    private static AchievementSummary titleSummary(XboxTitle title) {
        if (title.currentAchievements() == null || title.totalAchievements() == null) {
            return null;
        }
        int unlocked = title.currentAchievements();
        int total = title.totalAchievements();
        BigDecimal percent = total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(unlocked)
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        return new AchievementSummary(unlocked, total, percent, List.of(), null);
    }

    /** {@code gamerscore} / {@code gamerscoreTotal}: Titlehub's, else what the unlocked achievements add up to. */
    private static Map<String, Object> gamerscore(XboxTitle title, List<Achievement> all) {
        Map<String, Object> extra = new LinkedHashMap<>();
        Integer current = title.currentGamerscore() != null ? title.currentGamerscore()
                : all.isEmpty() ? null
                : all.stream().filter(Achievement::unlocked).mapToInt(a -> a.points() == null ? 0 : a.points()).sum();
        Integer total = title.totalGamerscore() != null ? title.totalGamerscore()
                : all.isEmpty() ? null : all.stream().mapToInt(a -> a.points() == null ? 0 : a.points()).sum();
        if (current != null) {
            extra.put("gamerscore", current);
        }
        if (total != null) {
            extra.put("gamerscoreTotal", total);
        }
        return extra;
    }

    /** A locked secret achievement discloses nothing, whatever the client read. */
    private static Achievement masked(Achievement a) {
        if (!a.secret() || a.unlocked()) {
            return a;
        }
        return new Achievement(a.id(), null, null, a.iconUrl(), false, null, a.rarity(), a.points(), true);
    }

    private boolean refused(Map<UUID, Instant> refusals, UUID userId) {
        Instant at = refusals.get(userId);
        if (at == null) {
            return false;
        }
        if (at.plus(REFUSAL_BACKOFF).isAfter(clock.instant())) {
            return true;
        }
        refusals.remove(userId, at);
        return false;
    }

    /** The history and the achievements are read together: with either source off, Xbox says nothing (no false status). */
    private boolean enabled() {
        return properties.source(EnrichmentSource.XBOX_TITLES).enabled()
                && properties.source(EnrichmentSource.XBOX_ACHIEVEMENTS).enabled();
    }

    private boolean linked(UserAccount user) {
        return oauthTokens.findByUserAndProvider(user, OAuthToken.Provider.XBOX).isPresent();
    }

    private Looked<Titles> history(UserAccount user, FetchBudget budget) {
        Supplier<XstsSession> session = session(user);
        return look(EnrichmentSource.XBOX_TITLES, "u:" + user.getId(), Titles.class, budget,
                () -> Optional.of(new Titles(client.titleHistory(user.getId(), session.get(), MAX_TITLES))));
    }

    /** The member's session, asked only when a call needs it; no session (standby, chain failing) is an outage. */
    private Supplier<XstsSession> session(UserAccount user) {
        return () -> tokens.flatMap(service -> service.getToken(user))
                .orElseThrow(() -> new XboxStatsUnavailableException("No Xbox session"));
    }

    /**
     * The cached or fetched value; when the fetch fails and nothing stale can be served, {@code failed} (an outage)
     * or {@code forbidden} (Xbox refused the session) says why the value is empty, as opposed to "absent".
     */
    private <T> Looked<T> look(EnrichmentSource source, String key, Class<T> type, FetchBudget budget,
                               Supplier<Optional<T>> fetch) {
        boolean[] why = new boolean[2];
        Optional<T> value = cache.get(source, key, type, budget, () -> {
            try {
                return fetch.get();
            } catch (XboxStatsUnavailableException e) {
                why[0] = true;
                throw e;
            } catch (XboxStatsForbiddenException e) {
                // Only a 403 is a lasting refusal; a 401 can be an expired token, so it is an outage.
                why[e.status() == 403 ? 1 : 0] = true;
                throw e;
            } catch (RuntimeException e) {
                // The cache swallows a fetch failure: record it, or a parsing error would read as "absent".
                why[0] = true;
                throw e;
            }
        });
        return new Looked<>(value, why[0], why[1]);
    }
}
