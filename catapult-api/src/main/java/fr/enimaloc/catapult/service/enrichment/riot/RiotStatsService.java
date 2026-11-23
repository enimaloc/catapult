package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.riot.RiotAccount;
import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.repository.riot.RiotAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champion;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champions;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LeagueEntry;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Leagues;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LiveGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LolSummoner;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats.CurrentGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats.LolStats;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats.Queue;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats.Rank;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats.Ranked;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats.TftStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.riot.RiotAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's personal stats on a Riot game (League of Legends: summoner level, solo and flex ranks, the game they are
 * in; Teamfight Tactics: rank, opt-in like its IGDB id; Valorant: {@code unsupported}, its API needs Riot Sign On),
 * through the shared cache under {@code u:<uuid>:<game>:<platform>} keys (no PUUID in a key, so none in a log), by
 * the PUUID of their declared Riot ID and with the key that resolved it. A summoner Riot does not know is cached
 * ({@code not_found}, under the source's TTL); an outage (any failure of the fetch) is never cached and says
 * {@code unavailable}; a game whose routes the key may not use (403) says {@code unsupported} and leaves the other
 * games alone. A PUUID gone stale (its key left the pool, or Riot cannot decrypt it) is resolved again once from the
 * Riot ID. The source off (disabled, or no key in the pool for the member's cluster) or no declared Riot ID gives no
 * object at all.
 */
@Slf4j
@Service
public class RiotStatsService {

    static final String SOLO = "RANKED_SOLO_5x5";
    static final String FLEX = "RANKED_FLEX_SR";
    static final String TFT = "RANKED_TFT";

    /** A summoner, or null for one Riot does not know (cached as a value, under the source's TTL). */
    public record SummonerCell(LolSummoner value) {
    }

    /** The ranked queues, or null when Riot does not know the PUUID there (cached as a value, source TTL). */
    public record LeagueCell(Leagues value) {
    }

    /** The game the member is in, or null for none. */
    public record LiveCell(LiveGame value) {
    }

    /**
     * A cached value, or why the upstream call gave none: it failed (an outage), the key may not use the routes, or
     * the PUUID is stale. None of these is cached.
     */
    private record Looked<T>(Optional<T> value, boolean failed, boolean forbidden, boolean stale) {

        Status absence() {
            return forbidden ? Status.UNSUPPORTED : failed ? Status.UNAVAILABLE : Status.NOT_FOUND;
        }
    }

    /** The PUUID must be resolved again. */
    private static final class Stale extends RuntimeException {
        Stale() {
            super(null, null, false, false);
        }
    }

    private final EnrichmentCache cache;
    private final RiotClient client;
    private final RiotApiKeyRotator keys;
    private final RiotAccountRepository accounts;
    private final RiotAccountService accountService;
    private final RiotGameDataService gameData;
    private final EnrichmentProperties properties;

    public RiotStatsService(EnrichmentCache cache, RiotClient client, RiotApiKeyRotator keys,
                            RiotAccountRepository accounts, RiotAccountService accountService,
                            RiotGameDataService gameData, EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.keys = keys;
        this.accounts = accounts;
        this.accountService = accountService;
        this.gameData = gameData;
        this.properties = properties;
    }

    /** The Riot game of an IGDB id, by the configured ids. */
    public Optional<RiotGame> gameOf(String igdbId) {
        EnrichmentProperties.Riot riot = properties.getRiot();
        return PublisherGames.riot(igdbId, riot.getLolIgdbId(), riot.getTftIgdbId(), riot.getValorantIgdbId());
    }

    /** The member's stats on the game being played, found by its IGDB id; empty when it is no Riot game. */
    public Optional<RiotStats> forDetected(UserAccount user, String igdbId, Locale locale, FetchBudget budget) {
        return gameOf(igdbId).flatMap(game -> forGame(user, game, locale, budget, false));
    }

    /** Teamfight Tactics is opt-in: served only once its IGDB id is configured (and a key can use its routes). */
    boolean tftOn() {
        String id = properties.getRiot().getTftIgdbId();
        return id != null && !id.isBlank();
    }

    /**
     * The member's stats on {@code game}; empty when there is nothing to say (the source is off, no key serves the
     * member's cluster, no Riot ID declared, Teamfight Tactics not opted in). {@code detail} also fills every ranked
     * queue.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<RiotStats> forGame(UserAccount user, RiotGame game, Locale locale, FetchBudget budget,
                                       boolean detail) {
        if (!properties.riotEnabled() || (game == RiotGame.TFT && !tftOn())) {
            return Optional.empty();
        }
        Optional<RiotAccount> row = accounts.findById(user.getId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Optional<RiotPlatform> platform = RiotPlatform.parse(row.get().getPlatform());
        if (platform.isEmpty() || !keys.serves(platform.get().cluster())) {
            return Optional.empty();
        }
        if (game == RiotGame.VALORANT) {
            return Optional.of(new RiotStats(Status.UNSUPPORTED, game, row.get().riotId(), platform.get().key(), null,
                    null, null));
        }
        String member = "u:" + user.getId();
        try {
            return play(game, row.get(), platform.get(), member, locale, budget, detail);
        } catch (Stale first) {
            Optional<RiotAccount> again;
            try {
                again = accountService.reresolve(user.getId());
            } catch (RuntimeException e) {
                log.warn("Riot PUUID of {} could not be resolved again: {}", user.getId(),
                        e.getClass().getSimpleName());
                return Optional.of(absent(game, row.get(), platform.get(), Status.UNAVAILABLE));
            }
            if (again.isEmpty()) {
                return Optional.of(absent(game, row.get(), platform.get(), Status.NOT_FOUND));
            }
            try {
                return play(game, again.get(), platform.get(), member, locale, budget, detail);
            } catch (Stale twice) {
                return Optional.of(absent(game, again.get(), platform.get(), Status.UNAVAILABLE));
            }
        }
    }

    private Optional<RiotStats> play(RiotGame game, RiotAccount account, RiotPlatform platform, String member,
                                     Locale locale, FetchBudget budget, boolean detail) {
        return game == RiotGame.LOL ? lol(account, platform, member, locale, budget, detail)
                : tft(account, platform, member, budget, detail);
    }

    private static RiotStats absent(RiotGame game, RiotAccount account, RiotPlatform platform, Status status) {
        return game == RiotGame.LOL
                ? of(game, account, platform, new LolStats(status, null, null, null, null, null, null), null)
                : of(game, account, platform, null, new TftStats(status, null, null));
    }

    private Optional<RiotStats> lol(RiotAccount account, RiotPlatform platform, String member, Locale locale,
                                    FetchBudget budget, boolean detail) {
        if (!properties.source(EnrichmentSource.RIOT_LOL_SUMMONER).enabled()) {
            return Optional.empty();
        }
        String puuid = account.getPuuid();
        String keyId = account.getPuuidKeyId();
        String key = member + ":lol:" + platform.key();
        Looked<LolSummoner> summoner = look(EnrichmentSource.RIOT_LOL_SUMMONER, key, SummonerCell.class,
                SummonerCell::new, SummonerCell::value, budget, () -> client.lolSummoner(platform, puuid, keyId));
        if (summoner.value().isEmpty()) {
            return Optional.of(absent(RiotGame.LOL, account, platform, summoner.absence()));
        }
        // The ranks and the game in progress are parts: without them the summoner is still served
        Looked<Leagues> leagues = look(EnrichmentSource.RIOT_LOL_LEAGUE, key, LeagueCell.class, LeagueCell::new,
                LeagueCell::value, budget, () -> new Leagues(client.lolLeague(platform, puuid, keyId)));
        // A league Riot does not know the PUUID in is unranked there; one that could not be read is unknown
        boolean leagueKnown = !leagues.failed() && !leagues.forbidden()
                && properties.source(EnrichmentSource.RIOT_LOL_LEAGUE).enabled();
        List<LeagueEntry> entries = leagues.value().map(Leagues::entries).orElse(List.of());
        Ranked ranked = leagues.value().isPresent() || leagueKnown
                ? new Ranked(rank(entries, SOLO), rank(entries, FLEX)) : null;
        Looked<LiveGame> live = look(EnrichmentSource.RIOT_LOL_LIVE, key, LiveCell.class, LiveCell::new,
                LiveCell::value, budget, () -> client.lolActiveGame(platform, puuid, keyId).orElse(null));
        // The live cell holds null for "in no game": known only when it was read
        Boolean inGame = live.failed() || live.forbidden() || !properties.source(EnrichmentSource.RIOT_LOL_LIVE)
                .enabled() ? null : live.value().isPresent();
        Optional<String> patch = gameData.patch(budget);
        CurrentGame current = live.value().map(g -> currentGame(g, patch, locale, budget)).orElse(null);
        LolSummoner s = summoner.value().get();
        String icon = s.profileIconId() == null ? null : patch.map(v -> HttpRiotClient.DDRAGON + "/cdn/" + v
                + "/img/profileicon/" + s.profileIconId() + ".png").orElse(null);
        List<Queue> queues = detail && ranked != null ? queues(entries) : null;
        return Optional.of(of(RiotGame.LOL, account, platform,
                new LolStats(Status.OK, s.level(), icon, ranked, inGame, current, queues), null));
    }

    private CurrentGame currentGame(LiveGame game, Optional<String> patch, Locale locale, FetchBudget budget) {
        Champion champion = null;
        if (game.championId() != null) {
            champion = gameData.champions(locale, budget)
                    .map(Champions::byKey).map(all -> all.get(String.valueOf(game.championId()))).orElse(null);
        }
        String icon = champion == null || patch.isEmpty() ? null
                : HttpRiotClient.championIconUrl(patch.get(), champion.id());
        return new CurrentGame(game.championId(), champion == null ? null : champion.name(), icon, game.gameMode(),
                game.queueId(), game.startedAt(), game.lengthSeconds());
    }

    private Optional<RiotStats> tft(RiotAccount account, RiotPlatform platform, String member, FetchBudget budget,
                                    boolean detail) {
        if (!properties.source(EnrichmentSource.RIOT_TFT_LEAGUE).enabled()) {
            return Optional.empty();
        }
        String puuid = account.getPuuid();
        String keyId = account.getPuuidKeyId();
        Looked<Leagues> leagues = look(EnrichmentSource.RIOT_TFT_LEAGUE, member + ":tft:" + platform.key(),
                LeagueCell.class, LeagueCell::new, LeagueCell::value, budget,
                () -> new Leagues(client.tftLeague(platform, puuid, keyId)));
        if (leagues.value().isEmpty()) {
            return Optional.of(absent(RiotGame.TFT, account, platform, leagues.absence()));
        }
        List<LeagueEntry> entries = leagues.value().get().entries();
        return Optional.of(of(RiotGame.TFT, account, platform, null,
                new TftStats(Status.OK, rank(entries, TFT), detail ? queues(entries) : null)));
    }

    private static Rank rank(List<LeagueEntry> entries, String queueType) {
        return entries.stream().filter(e -> queueType.equals(e.queueType())).findFirst().map(RiotStatsService::rank)
                .orElse(null);
    }

    private static Rank rank(LeagueEntry e) {
        return new Rank(e.tier(), e.rank(), e.leaguePoints(), e.wins(), e.losses());
    }

    private static List<Queue> queues(List<LeagueEntry> entries) {
        return entries.stream().map(e -> new Queue(e.queueType(), rank(e))).toList();
    }

    private static RiotStats of(RiotGame game, RiotAccount account, RiotPlatform platform, LolStats lol,
                                TftStats tft) {
        Status status = lol != null ? lol.status() : tft.status();
        return new RiotStats(status, game, account.riotId(), platform.key(), lol, tft, null);
    }

    /**
     * The cached or fetched value. A recognised "not found" is cached as a value (a cell with no content, under the
     * source's TTL); any other failure of the fetch is an outage, a forbidden route or a stale PUUID: flagged, never
     * cached. A stale PUUID ends the read ({@link Stale}).
     */
    private <T, W> Looked<T> look(EnrichmentSource source, String key, Class<W> cellType, Function<T, W> wrap,
                                  Function<W, T> unwrap, FetchBudget budget, Supplier<T> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean forbidden = new AtomicBoolean();
        AtomicBoolean stale = new AtomicBoolean();
        Optional<W> cell = cache.get(source, key, cellType, budget, () -> {
            try {
                return Optional.of(wrap.apply(fetch.get()));
            } catch (RiotNotFoundException e) {
                return Optional.of(wrap.apply(null));
            } catch (RiotStaleIdException e) {
                stale.set(true);
                throw e;
            } catch (RiotForbiddenException e) {
                forbidden.set(true);
                throw e;
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        if (stale.get()) {
            throw new Stale();
        }
        return new Looked<>(cell.map(unwrap), failed.get(), forbidden.get(), false);
    }
}
