package fr.enimaloc.catapult.service.enrichment.osu;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.osu.OsuAccount;
import fr.enimaloc.catapult.repository.osu.OsuAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.Me;
import fr.enimaloc.catapult.service.enrichment.osu.OsuStats.Profile;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.OsuGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.osu.OsuCredentialsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's personal stats on osu!, read with their own osu! token, through the shared cache under
 * {@code u:<uuid>:osu:...} keys. An outage (any failure of the fetch) is never cached and says {@code unavailable};
 * osu! has no "not found" or "private" for a member's own data. No application set, the source off, no link or a
 * link osu! refused give no object at all. osu! asks that a player be asked at most once a minute: each read of a
 * member is at least {@link #PER_PLAYER} after the previous one, whatever the cache TTL or a failure.
 */
@Service
public class OsuStatsService {

    /** The least time between two reads of one member's same data (osu!'s rule). */
    static final Duration PER_PLAYER = Duration.ofMinutes(1);
    /** The latest scores read: the aggregate shows the first, the detail all of them. */
    static final int RECENT = 5;

    /** The cached shapes. */
    public record MeCell(Me value) {
    }

    public record ScoresCell(List<OsuData.Score> value) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final OsuClient client;
    private final OsuTokenService tokens;
    private final OsuCredentialsService credentials;
    private final OsuAccountRepository accounts;
    private final EnrichmentProperties properties;
    private final Clock clock;
    private final Map<String, Instant> lastAsked = new ConcurrentHashMap<>();

    @Autowired
    public OsuStatsService(EnrichmentCache cache, OsuClient client, OsuTokenService tokens,
                           OsuCredentialsService credentials, OsuAccountRepository accounts,
                           EnrichmentProperties properties) {
        this(cache, client, tokens, credentials, accounts, properties, Clock.systemUTC());
    }

    OsuStatsService(EnrichmentCache cache, OsuClient client, OsuTokenService tokens, OsuCredentialsService credentials,
                    OsuAccountRepository accounts, EnrichmentProperties properties, Clock clock) {
        this.cache = cache;
        this.client = client;
        this.tokens = tokens;
        this.credentials = credentials;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    /** The member's stats for the game being played, when it is osu! (by IGDB id). */
    public Optional<OsuStats> forDetected(UserAccount user, String igdbId, FetchBudget budget) {
        return PublisherGames.osu(igdbId).flatMap(game -> forGame(user, game, budget, false));
    }

    /**
     * The member's stats on {@code game}; empty when there is nothing to say (no application, source off, no usable
     * link). {@code detail} also gives every ruleset's numbers and the latest scores.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<OsuStats> forGame(UserAccount user, OsuGame game, FetchBudget budget, boolean detail) {
        if (!credentials.available() || !properties.source(EnrichmentSource.OSU_PROFILE).enabled()) {
            return Optional.empty();
        }
        Optional<OsuAccount> row = accounts.findById(user.getId()).filter(OsuAccount::usable);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        OsuAccount account = row.get();
        UUID userId = user.getId();
        long osuId = account.getOsuUserId();
        String key = "u:" + userId + ":osu:" + osuId;
        AtomicBoolean refused = new AtomicBoolean();

        Looked<MeCell> me = look(EnrichmentSource.OSU_PROFILE, key, MeCell.class, budget,
                () -> new MeCell(asMember(userId, refused, client::me)));
        if (refused.get()) {
            return Optional.empty();
        }
        if (me.value().isEmpty() || me.value().get().value() == null) {
            return Optional.of(of(game, account.getUsername(), Profile.of(Status.UNAVAILABLE)));
        }
        Me m = me.value().get().value();
        if (m.id() != osuId) {
            // Never another account's numbers under this member (the token service checks every renewal too)
            return Optional.empty();
        }
        String ruleset = m.playmode() != null ? m.playmode()
                : account.getPlaymode() != null && OsuData.RULESETS.contains(account.getPlaymode())
                ? account.getPlaymode() : "osu";

        List<OsuData.Score> recent = null;
        if (properties.source(EnrichmentSource.OSU_RECENT).enabled()) {
            // The scores are a part: without them the profile is still served
            Looked<ScoresCell> got = look(EnrichmentSource.OSU_RECENT, key + ":recent:" + ruleset, ScoresCell.class,
                    budget, () -> new ScoresCell(asMember(userId, refused,
                            token -> client.recentScores(osuId, ruleset, RECENT, token))));
            recent = got.value().map(ScoresCell::value).orElse(null);
        }
        List<OsuStats.Score> scores = recent == null ? List.of()
                : recent.stream().map(OsuStatsService::score).toList();
        OsuData.Statistics s = m.statistics();
        Map<String, OsuStats.RulesetStats> rulesets = null;
        if (detail) {
            rulesets = new LinkedHashMap<>();
            for (String name : List.of("osu", "taiko", "fruits", "mania")) {
                OsuData.Statistics r = m.rulesets() == null ? null : m.rulesets().get(name);
                if (r != null) {
                    rulesets.put(name, new OsuStats.RulesetStats(r.pp(), r.globalRank(), r.countryRank(),
                            round(r.accuracy()), r.playCount(), r.playTimeSeconds(), r.level()));
                }
            }
        }
        Profile profile = new Profile(Status.OK, ruleset, s.pp(), s.globalRank(), s.countryRank(), m.countryCode(),
                round(s.accuracy()), s.playCount(), s.playTimeSeconds(), s.level(),
                scores.isEmpty() ? null : scores.getFirst(), rulesets, detail ? scores : null);
        return Optional.of(of(game, m.username() != null ? m.username() : account.getUsername(), profile));
    }

    private static OsuStats.Score score(OsuData.Score s) {
        OsuData.Beatmap b = s.beatmap();
        return new OsuStats.Score(b == null ? null : b.title(), b == null ? null : b.artist(),
                b == null ? null : b.version(), b == null ? null : b.starRating(), b == null ? null : b.url(),
                s.rank(), s.pp(), round(s.accuracy()), s.maxCombo(), s.mods(), s.endedAt());
    }

    /** A percentage to two decimals. */
    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }

    private static OsuStats of(OsuGame game, String account, Profile profile) {
        return new OsuStats(profile.status(), game, account, profile, null);
    }

    /**
     * {@code call} with the member's access token; a token osu! refuses is renewed once. Without a usable link
     * (none, or osu! refused the refresh just now) {@code refused} is set and the call fails.
     */
    private <T> T asMember(UUID userId, AtomicBoolean refused, Function<String, T> call) {
        for (int attempt = 0; ; attempt++) {
            Optional<String> token = tokens.accessToken(userId);
            if (token.isEmpty()) {
                refused.set(true);
                throw new OsuUnavailableException("the osu! link is not usable");
            }
            try {
                return call.apply(token.get());
            } catch (OsuExceptions.AuthRefused e) {
                tokens.refused(userId, token.get());
                if (attempt > 0) {
                    throw new OsuUnavailableException("osu! refused a renewed access token");
                }
            }
        }
    }

    /**
     * The cached or fetched value; any failure of the fetch is an outage: {@code failed} is set and nothing is
     * cached. A fetch less than {@link #PER_PLAYER} after the previous one of the same key is not sent: it fails.
     */
    private <T> Looked<T> look(EnrichmentSource source, String key, Class<T> type, FetchBudget budget,
                               Supplier<T> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        String asked = source.key() + "|" + key;
        Optional<T> value = cache.get(source, key, type, budget, () -> {
            try {
                if (!askNow(asked)) {
                    throw new OsuUnavailableException("this osu! player was asked less than a minute ago");
                }
                return Optional.of(fetch.get());
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(value, failed.get());
    }

    /** Takes the player's slot: false when the previous read is less than {@link #PER_PLAYER} old. */
    private boolean askNow(String key) {
        Instant now = clock.instant();
        AtomicBoolean taken = new AtomicBoolean();
        lastAsked.compute(key, (k, previous) -> {
            if (previous != null && previous.plus(PER_PLAYER).isAfter(now)) {
                return previous;
            }
            taken.set(true);
            return now;
        });
        if (lastAsked.size() > 10_000) {
            lastAsked.values().removeIf(at -> at.plus(PER_PLAYER).isBefore(now));
        }
        return taken.get();
    }
}
