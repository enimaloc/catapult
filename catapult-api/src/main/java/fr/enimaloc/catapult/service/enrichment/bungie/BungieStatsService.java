package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import fr.enimaloc.catapult.repository.bungie.BungieAccountRepository;
import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Profile;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieStats.Destiny2Stats;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * A member's personal stats on Destiny 2, read with their own Bungie token, through the shared cache under
 * {@code u:<uuid>:d2:...} keys. An absence or a privacy refusal Bungie confirmed is cached ({@code not_found},
 * {@code private}); an outage (any failure of the fetch) is never cached and says {@code unavailable}. No application
 * set, the source off, no link or a link Bungie refused give no object at all.
 */
@Service
public class BungieStatsService {

    static final String ACTIVITY = "DestinyActivityDefinition";
    static final String ACTIVITY_MODE = "DestinyActivityModeDefinition";
    private static final String ICONS = "https://www.bungie.net";

    /**
     * The cached shapes. A null value is a recognised "not found" (or, with {@code privateProfile}, a privacy
     * refusal), cached as a value under the source's own TTL.
     */
    public record ProfileCell(Profile value, boolean privateProfile) {
    }

    public record ScoreCell(Integer value) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final BungieClient client;
    private final BungieTokenService tokens;
    private final BungieCredentialsService credentials;
    private final BungieGameDataService gameData;
    private final BungieAccountRepository accounts;
    private final EnrichmentProperties properties;

    public BungieStatsService(EnrichmentCache cache, BungieClient client, BungieTokenService tokens,
                              BungieCredentialsService credentials, BungieGameDataService gameData,
                              BungieAccountRepository accounts, EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.tokens = tokens;
        this.credentials = credentials;
        this.gameData = gameData;
        this.accounts = accounts;
        this.properties = properties;
    }

    /** The member's stats for the game being played, when it is Destiny 2 (by IGDB id). */
    public Optional<BungieStats> forDetected(UserAccount user, String igdbId, Locale locale, FetchBudget budget) {
        return PublisherGames.bungie(igdbId).flatMap(game -> forGame(user, game, locale, budget, false));
    }

    /**
     * The member's stats on {@code game}; empty when there is nothing to say (no application, source off, no usable
     * link). {@code detail} also lists every character.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<BungieStats> forGame(UserAccount user, BungieGame game, Locale locale, FetchBudget budget,
                                         boolean detail) {
        if (!credentials.available() || !properties.source(EnrichmentSource.BUNGIE_PROFILE).enabled()) {
            return Optional.empty();
        }
        Optional<BungieAccount> row = accounts.findById(user.getId()).filter(BungieAccount::usable);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        BungieAccount account = row.get();
        if (account.getDestinyMembershipId() == null || account.getMembershipType() == null) {
            // Bungie listed no Destiny membership for the account when it was linked
            return Optional.of(of(game, account, Destiny2Stats.of(Status.NOT_FOUND)));
        }
        UUID userId = user.getId();
        int type = account.getMembershipType();
        String id = account.getDestinyMembershipId();
        String key = "u:" + userId + ":d2:" + type + ":" + id;
        AtomicBoolean refused = new AtomicBoolean();

        Looked<ProfileCell> profile = look(EnrichmentSource.BUNGIE_PROFILE, key, ProfileCell.class, budget, () -> {
            try {
                return new ProfileCell(asMember(userId, refused, token -> client.profile(type, id, token)), false);
            } catch (BungieExceptions.NotFound e) {
                return new ProfileCell(null, false);
            } catch (BungieExceptions.Private e) {
                return new ProfileCell(null, true);
            }
        });
        if (refused.get()) {
            return Optional.empty();
        }
        if (profile.value().isEmpty()) {
            return Optional.of(of(game, account, Destiny2Stats.of(profile.failed() ? Status.UNAVAILABLE
                    : Status.NOT_FOUND)));
        }
        ProfileCell cell = profile.value().get();
        if (cell.privateProfile() || (cell.value() != null && cell.value().profilePrivate())) {
            return Optional.of(of(game, account, Destiny2Stats.of(Status.PRIVATE)));
        }
        if (cell.value() == null) {
            return Optional.of(of(game, account, Destiny2Stats.of(Status.NOT_FOUND)));
        }
        Profile p = cell.value();
        Integer score = null;
        if (properties.source(EnrichmentSource.BUNGIE_TRIUMPHS).enabled()) {
            // The score is a part: without it the profile is still served
            Looked<ScoreCell> got = look(EnrichmentSource.BUNGIE_TRIUMPHS, key, ScoreCell.class, budget,
                    () -> {
                        try {
                            return new ScoreCell(asMember(userId, refused, token -> client.triumphScore(type, id,
                                    token)));
                        } catch (BungieExceptions.Private e) {
                            return new ScoreCell(null);
                        }
                    });
            score = got.value().map(ScoreCell::value).orElse(null);
        }
        List<BungieStats.Character> characters = p.characters().stream()
                .sorted(Comparator.comparing(BungieData.Character::lastPlayed,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(BungieStatsService::character).toList();
        String lc = BungieData.locale(locale);
        BungieStats.Activity activity = p.activity() == null ? null : new BungieStats.Activity(
                gameData.definitionName(ACTIVITY, p.activity().activityHash(), lc, budget).orElse(null),
                gameData.definitionName(ACTIVITY_MODE, p.activity().modeHash(), lc, budget).orElse(null),
                p.activity().startedAt());
        Destiny2Stats stats = new Destiny2Stats(Status.OK, BungieData.platform(type), p.guardianRank(), score,
                p.lastPlayed(), characters.isEmpty() ? null : characters.getFirst(), activity,
                detail ? characters : null);
        return Optional.of(of(game, account, stats));
    }

    private static BungieStats.Character character(BungieData.Character c) {
        return new BungieStats.Character(c.className(), c.light(), c.minutesPlayed(), c.lastPlayed(),
                c.emblemPath() == null ? null : ICONS + c.emblemPath());
    }

    private static BungieStats of(BungieGame game, BungieAccount account, Destiny2Stats destiny2) {
        return new BungieStats(destiny2.status(), game, account.getDisplayName(), destiny2, null);
    }

    /**
     * {@code call} with the member's access token; a token Bungie refuses is renewed once. Without a usable link
     * (none, or Bungie refused the refresh just now) {@code refused} is set and the call fails.
     */
    private <T> T asMember(UUID userId, AtomicBoolean refused, Function<String, T> call) {
        for (int attempt = 0; ; attempt++) {
            Optional<String> token = tokens.accessToken(userId);
            if (token.isEmpty()) {
                refused.set(true);
                throw new BungieUnavailableException("the Bungie link is not usable");
            }
            try {
                return call.apply(token.get());
            } catch (BungieExceptions.AuthRefused e) {
                tokens.refused(userId, token.get());
                if (attempt > 0) {
                    throw new BungieUnavailableException("Bungie refused a renewed access token");
                }
            }
        }
    }

    /**
     * The cached or fetched value; any failure of the fetch is an outage: {@code failed} is set and nothing is
     * cached.
     */
    private <T> Looked<T> look(EnrichmentSource source, String key, Class<T> type, FetchBudget budget,
                               java.util.function.Supplier<T> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        Optional<T> value = cache.get(source, key, type, budget, () -> {
            try {
                return Optional.of(fetch.get());
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(value, failed.get());
    }
}
