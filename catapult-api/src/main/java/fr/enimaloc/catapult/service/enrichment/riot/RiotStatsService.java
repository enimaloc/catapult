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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's personal stats on a Riot game (League of Legends: summoner level, solo and flex ranks, the game they are
 * in; Teamfight Tactics: rank; Valorant: {@code unsupported}, its API needs Riot Sign On), through the shared cache
 * under {@code u:<uuid>:...} keys, by the PUUID of their declared Riot ID. A summoner Riot does not know is cached
 * ({@code not_found}); an outage (any failure of the fetch) is never cached and says {@code unavailable}. The source
 * off (disabled, or no key in the pool for the member's cluster) or no declared Riot ID gives no object at all.
 */
@Service
public class RiotStatsService {

    static final String SOLO = "RANKED_SOLO_5x5";
    static final String FLEX = "RANKED_FLEX_SR";
    static final String TFT = "RANKED_TFT";

    /** A summoner, or null for one Riot does not know (cached as a value, under the source's TTL). */
    public record SummonerCell(LolSummoner value) {
    }

    /** The game the member is in, or null for none. */
    public record LiveCell(LiveGame value) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final RiotClient client;
    private final RiotApiKeyRotator keys;
    private final RiotAccountRepository accounts;
    private final RiotGameDataService gameData;
    private final EnrichmentProperties properties;

    public RiotStatsService(EnrichmentCache cache, RiotClient client, RiotApiKeyRotator keys,
                            RiotAccountRepository accounts, RiotGameDataService gameData,
                            EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.keys = keys;
        this.accounts = accounts;
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

    /**
     * The member's stats on {@code game}; empty when there is nothing to say (the source is off, no key serves the
     * member's cluster, no Riot ID declared). {@code detail} also fills every ranked queue.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<RiotStats> forGame(UserAccount user, RiotGame game, Locale locale, FetchBudget budget,
                                       boolean detail) {
        if (!properties.riotEnabled()) {
            return Optional.empty();
        }
        Optional<RiotAccount> row = accounts.findById(user.getId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        RiotAccount account = row.get();
        Optional<RiotPlatform> platform = RiotPlatform.parse(account.getPlatform());
        if (platform.isEmpty() || !keys.serves(platform.get().cluster())) {
            return Optional.empty();
        }
        String member = "u:" + user.getId();
        return switch (game) {
            case LOL -> lol(account, platform.get(), member, locale, budget, detail);
            case TFT -> tft(account, platform.get(), member, budget, detail);
            case VALORANT -> Optional.of(new RiotStats(Status.UNSUPPORTED, game, account.riotId(),
                    platform.get().key(), null, null, null));
        };
    }

    private Optional<RiotStats> lol(RiotAccount account, RiotPlatform platform, String member, Locale locale,
                                    FetchBudget budget, boolean detail) {
        if (!properties.source(EnrichmentSource.RIOT_LOL_SUMMONER).enabled()) {
            return Optional.empty();
        }
        String puuid = account.getPuuid();
        String key = member + ":lol:" + platform.key() + ":" + puuid;
        Looked<LolSummoner> summoner = look(EnrichmentSource.RIOT_LOL_SUMMONER, key, SummonerCell.class,
                SummonerCell::new, SummonerCell::value, budget, () -> client.lolSummoner(platform, puuid));
        if (summoner.value().isEmpty()) {
            Status status = summoner.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND;
            return Optional.of(of(RiotGame.LOL, account, platform,
                    new LolStats(status, null, null, null, null, null, null), null));
        }
        // The ranks and the game in progress are parts: without them the summoner is still served
        Looked<Leagues> leagues = look(EnrichmentSource.RIOT_LOL_LEAGUE, key, Leagues.class, l -> l, l -> l, budget,
                () -> new Leagues(client.lolLeague(platform, puuid)));
        Ranked ranked = leagues.value().map(l -> new Ranked(rank(l.entries(), SOLO), rank(l.entries(), FLEX)))
                .orElse(null);
        Looked<LiveGame> live = look(EnrichmentSource.RIOT_LOL_LIVE, key, LiveCell.class, LiveCell::new,
                LiveCell::value, budget, () -> client.lolActiveGame(platform, puuid).orElse(null));
        // The live cell holds null for "in no game": known only when it was read
        Boolean inGame = live.failed() || !properties.source(EnrichmentSource.RIOT_LOL_LIVE).enabled() ? null
                : live.value().isPresent();
        Optional<String> patch = gameData.patch(budget);
        CurrentGame current = live.value().map(g -> currentGame(g, patch, locale, budget)).orElse(null);
        LolSummoner s = summoner.value().get();
        String icon = s.profileIconId() == null ? null : patch.map(v -> HttpRiotClient.DDRAGON + "/cdn/" + v
                + "/img/profileicon/" + s.profileIconId() + ".png").orElse(null);
        List<Queue> queues = detail ? leagues.value().map(l -> queues(l.entries())).orElse(null) : null;
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
        Looked<Leagues> leagues = look(EnrichmentSource.RIOT_TFT_LEAGUE,
                member + ":tft:" + platform.key() + ":" + puuid, Leagues.class, l -> l, l -> l, budget,
                () -> new Leagues(client.tftLeague(platform, puuid)));
        if (leagues.value().isEmpty()) {
            Status status = leagues.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND;
            return Optional.of(of(RiotGame.TFT, account, platform, null, new TftStats(status, null, null)));
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
     * source's TTL); any other failure of the fetch is an outage: {@code failed} is set and nothing is cached.
     */
    private <T, W> Looked<T> look(EnrichmentSource source, String key, Class<W> cellType, Function<T, W> wrap,
                                  Function<W, T> unwrap, FetchBudget budget, Supplier<T> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        Optional<W> cell = cache.get(source, key, cellType, budget, () -> {
            try {
                return Optional.ofNullable(wrap.apply(fetch.get()));
            } catch (RiotNotFoundException e) {
                // a cell type holds the absence as a value; a plain value is cached as known absent
                return Optional.ofNullable(wrap.apply(null));
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(cell.map(unwrap), failed.get());
    }
}
